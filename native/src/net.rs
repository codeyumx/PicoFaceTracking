//! UDP helpers shared by the tracking connectors and the pairing listener.

use socket2::{Domain, Protocol, SockRef, Socket, Type};
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::time::{Duration, Instant};

/// Where the headset is asked when no address is configured.
pub const MULTICAST: Ipv4Addr = Ipv4Addr::new(239, 255, 255, 250);
/// Receives wait in slices of this length, so a stop or a new pairing key is noticed quickly.
const SLICE: Duration = Duration::from_millis(100);

/// A UDP socket on `0.0.0.0:port` (0: any free port) that may broadcast and does not hear its own multicasts.
pub fn udp_socket(port: u16) -> io::Result<UdpSocket> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, port)).into())?;
    socket.set_broadcast(true)?;
    socket.set_multicast_loop_v4(false)?;
    Ok(socket.into())
}

/// The headset's address: the configured one, else the multicast group.
pub fn target(address: Option<Ipv4Addr>, port: u16) -> SocketAddrV4 {
    SocketAddrV4::new(address.unwrap_or(MULTICAST), port)
}

/// Sends `message` to `target`; a multicast goes out on every IPv4 network card, as the .NET module does. Send errors
/// on single cards (for example one without a network) are skipped; an error is returned only when nothing went out.
pub fn send_discover(socket: &UdpSocket, message: &[u8], target: SocketAddrV4) -> io::Result<()> {
    if !target.ip().is_multicast() {
        return socket.send_to(message, target).map(drop);
    }
    let cards: Vec<Ipv4Addr> = if_addrs::get_if_addrs()
        .map(|all| {
            all.into_iter()
                .filter(|i| !i.is_loopback())
                .filter_map(|i| match i.ip() {
                    IpAddr::V4(v4) => Some(v4),
                    IpAddr::V6(_) => None,
                })
                .collect()
        })
        .unwrap_or_default();
    if cards.is_empty() {
        return socket.send_to(message, target).map(drop);
    }
    let sock = SockRef::from(socket);
    let mut last_error = None;
    let mut sent = false;
    for card in cards {
        match sock.set_multicast_if_v4(&card).and_then(|()| socket.send_to(message, target)) {
            Ok(_) => sent = true,
            Err(e) => last_error = Some(e),
        }
    }
    match (sent, last_error) {
        (false, Some(e)) => Err(e),
        _ => Ok(()),
    }
}

/// Outcome of [`recv_until`].
pub enum Recv {
    Datagram(usize, SocketAddr),
    /// The deadline passed without a datagram.
    TimedOut,
    /// `abort` returned true.
    Aborted,
}

/// Waits for one datagram until `deadline`, calling `abort` between short slices. Windows reports an earlier ICMP
/// "port unreachable" as a reset on the next receive; that is skipped, as the .NET module does.
pub fn recv_until(
    socket: &UdpSocket,
    buf: &mut [u8],
    deadline: Instant,
    abort: &mut dyn FnMut() -> bool,
) -> io::Result<Recv> {
    loop {
        if abort() {
            return Ok(Recv::Aborted);
        }
        let Some(left) = deadline.checked_duration_since(Instant::now()).filter(|d| !d.is_zero()) else {
            return Ok(Recv::TimedOut);
        };
        socket.set_read_timeout(Some(left.min(SLICE)))?;
        match socket.recv_from(buf) {
            Ok((len, from)) => return Ok(Recv::Datagram(len, from)),
            Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(e) if e.kind() == io::ErrorKind::ConnectionReset => {}
            Err(e) => return Err(e),
        }
    }
}

/// Private and link-local IPv4 addresses: pairing stays within the local network.
pub fn is_local(address: IpAddr) -> bool {
    let v4 = match address {
        IpAddr::V4(v4) => v4,
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => v4,
            None => return false,
        },
    };
    v4.is_private() || v4.is_link_local()
}
