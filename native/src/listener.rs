//! Pairing by code (`module/docs/protocol-v2.md`): asks the network every 2 seconds whether a headset wants to pair,
//! shows the code for it, and saves the new key once the user typed the code on the headset. Runs on its own thread
//! for as long as the module. Port of the .NET module's `PairingListener`.

use crate::exchange::{self, Exchange, Outcome};
use crate::net::{self, Recv};
use crate::pairing::{self, Pairing};
use fb_module_sdk::LogLevel;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

const MAX_FAILURES: u32 = 5;
const ROUND: Duration = Duration::from_secs(2);
const ATTEMPT_LIFETIME: Duration = Duration::from_secs(5 * 60);
const PAUSE_AFTER_WRONG_CODE: Duration = Duration::from_secs(10);

/// What the listener reports to the module thread.
pub enum Event {
    Log(LogLevel, String),
    /// Short form for the "Pairing" text in the settings panel.
    Status(String),
    /// A headset on the local network offered to pair, so a headset is there.
    Offered,
    /// A headset paired by code; the key is saved already.
    Paired(Pairing),
}

pub struct Listener {
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl Listener {
    pub fn spawn(address: Option<Ipv4Addr>, port: u16, events: Sender<Event>) -> std::io::Result<Self> {
        let socket = net::udp_socket(0)?;
        let stop = Arc::new(AtomicBool::new(false));
        let state = State {
            socket,
            target: net::target(address, port),
            events,
            stop: stop.clone(),
            pc_name: gethostname::gethostname().to_string_lossy().into_owned(),
            attempt: None,
            failures: 0,
            paused_until: None,
        };
        let thread = std::thread::Builder::new().name("pairing".into()).spawn(move || state.run())?;
        Ok(Self { stop, thread: Some(thread) })
    }
}

impl Drop for Listener {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}

struct Attempt {
    exchange: Exchange,
    headset: SocketAddr,
    headset_name: String,
    started: Instant,
    /// After pairing: the RESPONSE and the CONFIRM sent for it, repeated if the headset sends it again.
    paired: Option<(Vec<u8>, Vec<u8>)>,
}

struct State {
    socket: UdpSocket,
    target: std::net::SocketAddrV4,
    events: Sender<Event>,
    stop: Arc<AtomicBool>,
    pc_name: String,
    attempt: Option<Attempt>,
    failures: u32,
    paused_until: Option<Instant>,
}

fn spaced(code: &str) -> String {
    format!("{} {}", &code[..3], &code[3..])
}

impl State {
    fn send(&self, event: Event) {
        let _ = self.events.send(event);
    }

    fn run(mut self) {
        let discover = exchange::discover_message();
        let mut buf = vec![0u8; 2048];
        while !self.stop.load(Ordering::SeqCst) {
            if let Err(e) = net::send_discover(&self.socket, &discover, self.target) {
                // For example no network yet; try again next round.
                self.send(Event::Log(LogLevel::Debug, format!("Pairing: {e}")));
            }
            let round_end = Instant::now() + ROUND;
            let stop = self.stop.clone();
            loop {
                match net::recv_until(&self.socket, &mut buf, round_end, &mut || stop.load(Ordering::SeqCst)) {
                    Ok(Recv::Datagram(len, from)) => self.handle(&buf[..len], from),
                    Ok(Recv::TimedOut) => break,
                    Ok(Recv::Aborted) => return,
                    Err(e) => {
                        self.send(Event::Log(LogLevel::Debug, format!("Pairing: {e}")));
                        std::thread::sleep(ROUND);
                        break;
                    }
                }
            }
        }
    }

    fn handle(&mut self, message: &[u8], from: SocketAddr) {
        if !net::is_local(from.ip()) {
            return;
        }
        match pairing::type_of(message) {
            Some(exchange::OFFER) => self.offer(message, from),
            Some(exchange::RESPONSE) => self.response(message, from),
            _ => {}
        }
    }

    fn offer(&mut self, message: &[u8], from: SocketAddr) {
        let Some((headset_nonce, headset_name)) = exchange::parse_offer(message) else { return };
        self.send(Event::Offered);
        if let Some(a) = &self.attempt {
            if a.headset == from && *a.exchange.headset_nonce() == headset_nonce {
                // The headset still waits for the code; it may have missed the START.
                if a.paired.is_none() {
                    let _ = self.socket.send_to(&a.exchange.start_message, from);
                }
                return;
            }
            // One headset at a time; the same headset may start over.
            if a.paired.is_none() && a.headset.ip() != from.ip() && a.started.elapsed() < ATTEMPT_LIFETIME {
                return;
            }
        }
        if self.failures >= MAX_FAILURES || self.paused_until.is_some_and(|t| Instant::now() < t) {
            return;
        }
        let exchange = Exchange::new(headset_nonce, &self.pc_name);
        let code = spaced(&exchange.code);
        self.send(Event::Log(
            LogLevel::Information,
            format!("Pairing code for {headset_name} ({}): {code}. Type it in Pico Face Tracking on the headset.", from.ip()),
        ));
        self.send(Event::Status(format!(
            "Code for {headset_name} ({}): {code}\nType it in Pico Face Tracking on the headset.",
            from.ip()
        )));
        let _ = self.socket.send_to(&exchange.start_message, from);
        self.attempt = Some(Attempt { exchange, headset: from, headset_name, started: Instant::now(), paired: None });
    }

    fn response(&mut self, message: &[u8], from: SocketAddr) {
        let Some(a) = &mut self.attempt else { return };
        if a.headset != from {
            return;
        }
        if let Some((response, confirm)) = &a.paired {
            // The headset did not get the CONFIRM.
            if response == message {
                let _ = self.socket.send_to(confirm, from);
            }
            return;
        }
        match a.exchange.finish(message) {
            Outcome::NotForThis => {}
            Outcome::WrongCode(reject) => {
                let _ = self.socket.send_to(&reject, from);
                let name = a.headset_name.clone();
                self.attempt = None;
                self.failures += 1;
                self.paused_until = Some(Instant::now() + PAUSE_AFTER_WRONG_CODE);
                let text = if self.failures < MAX_FAILURES {
                    format!("A wrong pairing code was typed on {name} ({}). A new code follows in a few seconds.", from.ip())
                } else {
                    format!("A wrong pairing code was typed {MAX_FAILURES} times. Pairing is off until the module restarts.")
                };
                self.send(Event::Log(LogLevel::Warning, text.clone()));
                self.send(Event::Status(text));
            }
            Outcome::Paired { key, confirm } => {
                let _ = self.socket.send_to(&confirm, from);
                a.paired = Some((message.to_vec(), confirm));
                let name = a.headset_name.clone();
                match Pairing::save(&key) {
                    Ok(pairing) => {
                        let path = pairing::key_path().map(|p| p.display().to_string()).unwrap_or_default();
                        self.send(Event::Log(
                            LogLevel::Information,
                            format!("Paired with {name} ({}). The pairing key is saved in {path}.", from.ip()),
                        ));
                        self.send(Event::Status(format!("Paired with {name} ({}).", from.ip())));
                        self.send(Event::Paired(pairing));
                    }
                    Err(e) => self.send(Event::Log(LogLevel::Error, format!("Pairing failed: {e}"))),
                }
            }
        }
    }
}
