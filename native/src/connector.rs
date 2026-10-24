//! The tracking connection to the headset: protocol version 1 (thoricelli's daemon protocol, PC found by address)
//! or, with a pairing key, version 2 (authenticated handshake, encrypted frames). Port of the .NET module's
//! `PicoFacialModuleConnector` and `PairedConnector`.

use crate::net::{self, Recv};
use crate::pairing::{self, Nonce16, Pairing, Session};
use crate::payload::FRAME_LEN;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::time::{Duration, Instant};

/// UDP port of the headset (and of the version 1 PC side).
pub const PORT: u16 = 9030;
/// A new DISCOVER goes out this often while no headset answers.
const DISCOVERY_ROUND: Duration = Duration::from_secs(2);
/// Nothing at all from the headset for this long: it is gone.
const RECEIVE_TIMEOUT: Duration = Duration::from_secs(2);

const V1_DISCOVER: &[u8] = b"DISCOVER_DAEMON";
/// Version 1 keep-alive from the headset: `MARCO` plus a terminator.
const V1_PING_LEN: usize = 6;
const V1_PONG: &[u8] = b"POLO";
const V1_STOP: &[u8] = b"STOP";

// One connector exists at a time; boxing the version 2 state would only add an indirection.
#[allow(clippy::large_enum_variant)]
enum Proto {
    V1 {
        /// The first datagram, received while discovering; handled by the next `receive`.
        pending: Option<usize>,
    },
    V2 {
        pairing: Pairing,
        session: Option<Session>,
        model: Option<String>,
    },
}

/// What one [`Connector::receive`] got.
#[derive(Debug, PartialEq, Eq)]
pub enum Received {
    /// A tracking frame, in [`Connector::frame`].
    Frame,
    /// A keep-alive (answered) or a datagram without tracking data.
    Nothing,
    /// The caller's `abort` asked to stop waiting.
    Aborted,
}

/// The headset stopped answering (or the socket failed): discover again.
#[derive(Debug)]
pub struct Died;

pub struct Connector {
    socket: UdpSocket,
    target: SocketAddrV4,
    proto: Proto,
    headset: Option<SocketAddr>,
    buf: Vec<u8>,
    plain: Vec<u8>,
    frame: [u8; FRAME_LEN],
}

impl Connector {
    /// Version 1 listens on the fixed port 9030 (the headset streams there); version 2 uses any free port, since the
    /// headset answers wherever the DISCOVER came from.
    pub fn new(address: Option<Ipv4Addr>, pairing: Option<Pairing>) -> io::Result<Self> {
        let local = if pairing.is_some() { 0 } else { PORT };
        Self::with_ports(local, PORT, address, pairing)
    }

    fn with_ports(local: u16, remote: u16, address: Option<Ipv4Addr>, pairing: Option<Pairing>) -> io::Result<Self> {
        let proto = match pairing {
            None => Proto::V1 { pending: None },
            Some(pairing) => Proto::V2 { pairing, session: None, model: None },
        };
        Ok(Self {
            socket: net::udp_socket(local)?,
            target: net::target(address, remote),
            proto,
            headset: None,
            buf: vec![0; 2048],
            plain: Vec::with_capacity(FRAME_LEN),
            frame: [0; FRAME_LEN],
        })
    }

    pub fn is_established(&self) -> bool {
        self.headset.is_some()
    }

    pub fn frame(&self) -> &[u8; FRAME_LEN] {
        &self.frame
    }

    /// Forgets the headset, so the next step is discovery.
    pub fn reset(&mut self) {
        self.headset = None;
        match &mut self.proto {
            Proto::V1 { pending } => *pending = None,
            Proto::V2 { session, .. } => *session = None,
        }
    }

    /// Asks for the headset (a new DISCOVER every 2 s) until one answers, `until` passes or `abort` returns true.
    pub fn establish(&mut self, until: Instant, abort: &mut dyn FnMut() -> bool) -> io::Result<Option<SocketAddr>> {
        self.reset();
        while Instant::now() < until {
            let pc_nonce: Nonce16 = rand::random();
            let discover = match &self.proto {
                Proto::V1 { .. } => V1_DISCOVER.to_vec(),
                Proto::V2 { pairing, .. } => pairing.discover(&pc_nonce),
            };
            net::send_discover(&self.socket, &discover, self.target)?;
            let round_end = (Instant::now() + DISCOVERY_ROUND).min(until);
            loop {
                let (len, from) = match net::recv_until(&self.socket, &mut self.buf, round_end, abort)? {
                    Recv::Datagram(len, from) => (len, from),
                    Recv::TimedOut => break,
                    Recv::Aborted => return Ok(None),
                };
                match &mut self.proto {
                    Proto::V1 { pending } => {
                        *pending = Some(len);
                        self.headset = Some(from);
                        return Ok(Some(from));
                    }
                    Proto::V2 { pairing, session, .. } => {
                        let Some(headset_nonce) = pairing.verify_hello(&self.buf[..len], &pc_nonce) else { continue };
                        self.socket.send_to(&pairing.start(&pc_nonce, &headset_nonce), from)?;
                        *session = Some(pairing.session(&pc_nonce, &headset_nonce));
                        self.headset = Some(from);
                        return Ok(Some(from));
                    }
                }
            }
        }
        Ok(None)
    }

    /// Waits for the next message from the established headset. `on_model` gets the headset's model name (version 2
    /// INFO) when it first arrives or changes.
    pub fn receive(
        &mut self,
        abort: &mut dyn FnMut() -> bool,
        on_model: &mut dyn FnMut(&str),
    ) -> Result<Received, Died> {
        let headset = self.headset.ok_or(Died)?;
        if let Proto::V1 { pending } = &mut self.proto {
            if let Some(len) = pending.take() {
                return Ok(self.v1_message(len, headset));
            }
        }
        loop {
            let deadline = Instant::now() + RECEIVE_TIMEOUT;
            let (len, from) = match net::recv_until(&self.socket, &mut self.buf, deadline, abort) {
                Ok(Recv::Datagram(len, from)) => (len, from),
                Ok(Recv::Aborted) => return Ok(Received::Aborted),
                Ok(Recv::TimedOut) | Err(_) => return Err(Died),
            };
            match &mut self.proto {
                Proto::V1 { .. } => {
                    if from.ip() != headset.ip() {
                        continue;
                    }
                    return Ok(self.v1_message(len, headset));
                }
                Proto::V2 { session, model, .. } => {
                    if from != headset {
                        continue;
                    }
                    let Some(session) = session else { return Err(Died) };
                    match session.open(&self.buf[..len], &mut self.plain, FRAME_LEN) {
                        Some(pairing::PING) => {
                            let pong = session.seal(pairing::PONG);
                            let _ = self.socket.send_to(&pong, headset);
                            return Ok(Received::Nothing);
                        }
                        Some(pairing::INFO) => {
                            if let Some(name) = pairing::parse_info(&self.plain) {
                                if model.as_deref() != Some(name.as_str()) {
                                    on_model(&name);
                                    *model = Some(name);
                                }
                            }
                        }
                        Some(pairing::DATA) => {
                            return Ok(match <[u8; FRAME_LEN]>::try_from(self.plain.as_slice()) {
                                Ok(frame) => {
                                    self.frame = frame;
                                    Received::Frame
                                }
                                Err(_) => Received::Nothing,
                            });
                        }
                        _ => {}
                    }
                }
            }
        }
    }

    fn v1_message(&mut self, len: usize, headset: SocketAddr) -> Received {
        if len == V1_PING_LEN {
            let _ = self.socket.send_to(V1_PONG, headset);
            return Received::Nothing;
        }
        match <[u8; FRAME_LEN]>::try_from(&self.buf[..len]) {
            Ok(frame) => {
                self.frame = frame;
                Received::Frame
            }
            Err(_) => Received::Nothing,
        }
    }
}

impl Drop for Connector {
    /// Stops the tracking on the headset, if one was found; the socket closes with the connector.
    fn drop(&mut self) {
        let Some(headset) = self.headset else { return };
        let stop = match &mut self.proto {
            Proto::V1 { .. } => V1_STOP.to_vec(),
            Proto::V2 { session: Some(session), .. } => session.seal(pairing::STOP),
            Proto::V2 { session: None, .. } => return,
        };
        let _ = self.socket.send_to(&stop, headset);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pairing::tests::HeadsetSession;

    fn headset_socket() -> UdpSocket {
        let s = UdpSocket::bind("127.0.0.1:0").unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        s
    }

    fn recv(s: &UdpSocket) -> (Vec<u8>, SocketAddr) {
        let mut buf = [0u8; 2048];
        let (len, from) = s.recv_from(&mut buf).unwrap();
        (buf[..len].to_vec(), from)
    }

    fn never() -> bool {
        false
    }

    #[test]
    fn v1_answers_pings_and_receives_frames_then_stops_the_headset() {
        let headset = headset_socket();
        let port = headset.local_addr().unwrap().port();
        let mut c = Connector::with_ports(0, port, Some(Ipv4Addr::LOCALHOST), None).unwrap();
        let fake = std::thread::spawn(move || {
            let (discover, pc) = recv(&headset);
            assert_eq!(discover, V1_DISCOVER);
            headset.send_to(b"MARCO\0", pc).unwrap();
            assert_eq!(recv(&headset).0, V1_PONG);
            headset.send_to(&[7u8; FRAME_LEN], pc).unwrap();
            assert_eq!(recv(&headset).0, V1_STOP);
        });
        let until = Instant::now() + Duration::from_secs(5);
        assert!(c.establish(until, &mut never).unwrap().is_some());
        assert_eq!(c.receive(&mut never, &mut |_| {}).unwrap(), Received::Nothing);
        assert_eq!(c.receive(&mut never, &mut |_| {}).unwrap(), Received::Frame);
        assert_eq!(c.frame()[0], 7);
        drop(c);
        fake.join().unwrap();
    }

    #[test]
    fn v2_handshake_info_frames_pong_and_stop() {
        let key = [3u8; 32];
        let headset = headset_socket();
        let port = headset.local_addr().unwrap().port();
        let mut c = Connector::with_ports(0, port, Some(Ipv4Addr::LOCALHOST), Some(Pairing::from_key(&key))).unwrap();
        let fake = std::thread::spawn(move || {
            let (discover, pc) = recv(&headset);
            let pc_nonce: Nonce16 = discover[5..21].try_into().unwrap();
            let hs_nonce = [9u8; 16];
            headset.send_to(&HeadsetSession::hello(&key, &pc_nonce, &hs_nonce), pc).unwrap();
            let (start, _) = recv(&headset);
            assert_eq!(pairing::type_of(&start), Some(pairing::START));
            let mut session = HeadsetSession::new(&key, &pc_nonce, &hs_nonce);
            headset.send_to(&session.seal(pairing::INFO, b"\x0aPICO 4 Pro"), pc).unwrap();
            headset.send_to(&session.seal(pairing::DATA, &[5u8; FRAME_LEN]), pc).unwrap();
            headset.send_to(&session.seal(pairing::PING, b""), pc).unwrap();
            assert_eq!(pairing::type_of(&recv(&headset).0), Some(pairing::PONG));
            assert_eq!(pairing::type_of(&recv(&headset).0), Some(pairing::STOP));
        });
        let until = Instant::now() + Duration::from_secs(5);
        assert!(c.establish(until, &mut never).unwrap().is_some());
        let mut models = Vec::new();
        assert_eq!(c.receive(&mut never, &mut |m| models.push(m.to_owned())).unwrap(), Received::Frame);
        assert_eq!(c.frame()[0], 5);
        assert_eq!(models, ["PICO 4 Pro"]);
        assert_eq!(c.receive(&mut never, &mut |_| {}).unwrap(), Received::Nothing);
        drop(c);
        fake.join().unwrap();
    }

    #[test]
    fn silent_headset_is_reported_dead_and_abort_ends_discovery() {
        let headset = headset_socket();
        let port = headset.local_addr().unwrap().port();
        let mut c = Connector::with_ports(0, port, Some(Ipv4Addr::LOCALHOST), None).unwrap();
        let mut stop = || true;
        assert!(c.establish(Instant::now() + Duration::from_secs(5), &mut stop).unwrap().is_none());
        let (_, pc) = recv(&headset);
        let fake = std::thread::spawn(move || {
            recv(&headset);
            headset.send_to(b"MARCO\0", pc).unwrap();
        });
        assert!(c.establish(Instant::now() + Duration::from_secs(5), &mut never).unwrap().is_some());
        fake.join().unwrap();
        assert_eq!(c.receive(&mut never, &mut |_| {}).unwrap(), Received::Nothing);
        let started = Instant::now();
        assert!(c.receive(&mut never, &mut |_| {}).is_err());
        assert!(started.elapsed() >= RECEIVE_TIMEOUT);
    }
}
