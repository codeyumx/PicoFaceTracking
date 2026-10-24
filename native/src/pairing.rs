//! Protocol version 2 (`module/docs/protocol-v2.md`): the pairing key authenticates the handshake and encrypts the
//! session. Port of the .NET module's `Pairing`; the key file is shared with it.

use aes_gcm::aead::{AeadInPlace, KeyInit};
use aes_gcm::{Aes256Gcm, Nonce, Tag};
use base64::Engine as _;
use base64::engine::general_purpose::STANDARD as BASE64;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use std::path::PathBuf;
use subtle::ConstantTimeEq;

pub const HELLO: u8 = 2;
pub const START: u8 = 3;
pub const DISCOVER: u8 = 1;
pub const DATA: u8 = 4;
pub const PING: u8 = 5;
pub const PONG: u8 = 6;
pub const STOP: u8 = 7;
/// Session message from the headset: its model name (length byte, then UTF-8).
pub const INFO: u8 = 14;
const MAX_INFO_NAME: usize = 64;

pub const MAGIC: &[u8; 4] = b"PFT2";
pub const HEADER_LEN: usize = 5;
pub const NONCE_LEN: usize = 16;
const MAC_LEN: usize = 16;
const COUNTER_LEN: usize = 8;
const TAG_LEN: usize = 16;
pub const KEY_LEN: usize = 32;
const DISCOVER_LEN: usize = HEADER_LEN + NONCE_LEN + MAC_LEN;
const HANDSHAKE_LEN: usize = HEADER_LEN + 2 * NONCE_LEN + MAC_LEN;
const SESSION_OVERHEAD: usize = HEADER_LEN + COUNTER_LEN + TAG_LEN;

const FROM_HEADSET: &[u8; 4] = b"HMD\0";
const FROM_PC: &[u8; 4] = b"PC\0\0";

pub type Nonce16 = [u8; NONCE_LEN];

pub fn hmac(key: &[u8], parts: &[&[u8]]) -> [u8; 32] {
    let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(key).expect("HMAC takes any key length");
    for part in parts {
        mac.update(part);
    }
    mac.finalize().into_bytes().into()
}

/// The message type after the `PFT2` header, or `None` for anything else.
pub fn type_of(message: &[u8]) -> Option<u8> {
    (message.len() >= HEADER_LEN && &message[..4] == MAGIC).then(|| message[4])
}

pub fn header(message: &mut [u8], ty: u8) {
    message[..4].copy_from_slice(MAGIC);
    message[4] = ty;
}

/// The model name in the plaintext of an INFO message, or `None` when it does not fit the format.
pub fn parse_info(plaintext: &[u8]) -> Option<String> {
    let (&len, name) = plaintext.split_first()?;
    if usize::from(len) > MAX_INFO_NAME || name.len() != usize::from(len) {
        return None;
    }
    String::from_utf8(name.to_vec()).ok()
}

/// `%APPDATA%\PicoFacialData\pairing-key.txt` (Linux: `~/.config/PicoFacialData/pairing-key.txt`), the file the .NET
/// module uses too, so a pairing survives switching between them.
pub fn key_path() -> Option<PathBuf> {
    dirs::config_dir().map(|d| d.join("PicoFacialData").join("pairing-key.txt"))
}

pub struct Pairing {
    auth_key: [u8; 32],
    enc_key: [u8; 32],
}

impl Pairing {
    pub fn from_key(key: &[u8; KEY_LEN]) -> Self {
        Self { auth_key: hmac(key, &[b"PFT2 auth"]), enc_key: hmac(key, &[b"PFT2 enc"]) }
    }

    /// The pairing in the key file, or `None` when there is none.
    pub fn load() -> Result<Option<Self>, String> {
        let Some(path) = key_path() else { return Ok(None) };
        let text = match std::fs::read_to_string(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(format!("reading {}: {e}", path.display())),
        };
        let key = BASE64
            .decode(text.trim())
            .map_err(|_| format!("{} does not contain a Base64 key.", path.display()))?;
        let key: [u8; KEY_LEN] =
            key.try_into().map_err(|_| format!("{} must contain a {KEY_LEN}-byte key.", path.display()))?;
        Ok(Some(Self::from_key(&key)))
    }

    /// Saves a new key in the key file, replacing the old one.
    pub fn save(key: &[u8; KEY_LEN]) -> Result<Self, String> {
        let path = key_path().ok_or("no user configuration folder")?;
        let write = || -> std::io::Result<()> {
            std::fs::create_dir_all(path.parent().expect("key path has a parent"))?;
            let temporary = path.with_extension("txt.new");
            std::fs::write(&temporary, BASE64.encode(key))?;
            std::fs::rename(&temporary, &path)
        };
        write().map_err(|e| format!("saving {}: {e}", path.display()))?;
        Ok(Self::from_key(key))
    }

    fn mac(&self, data: &[u8]) -> [u8; MAC_LEN] {
        hmac(&self.auth_key, &[data])[..MAC_LEN].try_into().expect("16 bytes")
    }

    pub fn discover(&self, pc_nonce: &Nonce16) -> Vec<u8> {
        let mut m = vec![0u8; DISCOVER_LEN];
        header(&mut m, DISCOVER);
        m[HEADER_LEN..HEADER_LEN + NONCE_LEN].copy_from_slice(pc_nonce);
        let mac = self.mac(&m[..HEADER_LEN + NONCE_LEN]);
        m[HEADER_LEN + NONCE_LEN..].copy_from_slice(&mac);
        m
    }

    /// The headset's nonce when `message` is a valid HELLO for this DISCOVER.
    pub fn verify_hello(&self, message: &[u8], pc_nonce: &Nonce16) -> Option<Nonce16> {
        if message.len() != HANDSHAKE_LEN || type_of(message) != Some(HELLO) {
            return None;
        }
        let signed = &message[..HEADER_LEN + 2 * NONCE_LEN];
        let mac_ok = self.mac(signed).ct_eq(&message[signed.len()..]);
        let nonce_ok = message[HEADER_LEN..HEADER_LEN + NONCE_LEN].ct_eq(pc_nonce);
        if !bool::from(mac_ok & nonce_ok) {
            return None;
        }
        Some(message[HEADER_LEN + NONCE_LEN..HEADER_LEN + 2 * NONCE_LEN].try_into().expect("16 bytes"))
    }

    pub fn start(&self, pc_nonce: &Nonce16, headset_nonce: &Nonce16) -> Vec<u8> {
        let mut m = vec![0u8; HANDSHAKE_LEN];
        header(&mut m, START);
        m[HEADER_LEN..HEADER_LEN + NONCE_LEN].copy_from_slice(pc_nonce);
        m[HEADER_LEN + NONCE_LEN..HEADER_LEN + 2 * NONCE_LEN].copy_from_slice(headset_nonce);
        let mac = self.mac(&m[..HEADER_LEN + 2 * NONCE_LEN]);
        m[HEADER_LEN + 2 * NONCE_LEN..].copy_from_slice(&mac);
        m
    }

    pub fn session(&self, pc_nonce: &Nonce16, headset_nonce: &Nonce16) -> Session {
        let key = hmac(&self.enc_key, &[pc_nonce, headset_nonce]);
        Session { aes: Aes256Gcm::new_from_slice(&key).expect("32-byte key"), sent: 0, received: 0 }
    }
}

/// One encrypted session: AES-256-GCM with a counter nonce per direction.
pub struct Session {
    aes: Aes256Gcm,
    sent: u64,
    received: u64,
}

fn nonce(direction: &[u8; 4], counter: u64) -> [u8; 12] {
    let mut n = [0u8; 12];
    n[..4].copy_from_slice(direction);
    n[4..].copy_from_slice(&counter.to_le_bytes());
    n
}

impl Session {
    /// Opens a message from the headset into `plaintext` (at most `max_len` bytes). Returns its type, or `None` when
    /// it is not authentic, is a replay, or is too long.
    pub fn open(&mut self, message: &[u8], plaintext: &mut Vec<u8>, max_len: usize) -> Option<u8> {
        let ty = type_of(message)?;
        if message.len() < SESSION_OVERHEAD {
            return None;
        }
        let counter = u64::from_le_bytes(message[HEADER_LEN..HEADER_LEN + COUNTER_LEN].try_into().expect("8 bytes"));
        let len = message.len() - SESSION_OVERHEAD;
        if counter <= self.received || len > max_len {
            return None;
        }
        let (body, tag) = message[HEADER_LEN + COUNTER_LEN..].split_at(len);
        plaintext.clear();
        plaintext.extend_from_slice(body);
        let aad = &message[..HEADER_LEN + COUNTER_LEN];
        self.aes
            .decrypt_in_place_detached(Nonce::from_slice(&nonce(FROM_HEADSET, counter)), aad, plaintext, Tag::from_slice(tag))
            .ok()?;
        self.received = counter;
        Some(ty)
    }

    /// A message to the headset without a body (PONG, STOP).
    pub fn seal(&mut self, ty: u8) -> Vec<u8> {
        self.sent += 1;
        let mut m = vec![0u8; SESSION_OVERHEAD];
        header(&mut m, ty);
        m[HEADER_LEN..HEADER_LEN + COUNTER_LEN].copy_from_slice(&self.sent.to_le_bytes());
        let tag = self
            .aes
            .encrypt_in_place_detached(Nonce::from_slice(&nonce(FROM_PC, self.sent)), &m[..HEADER_LEN + COUNTER_LEN], &mut [])
            .expect("an empty message always encrypts");
        m[HEADER_LEN + COUNTER_LEN..].copy_from_slice(&tag);
        m
    }
}

#[cfg(test)]
pub mod tests {
    use super::*;

    /// The headset's side of a session, for tests: seals messages the way the app does.
    pub struct HeadsetSession {
        aes: Aes256Gcm,
        sent: u64,
    }

    impl HeadsetSession {
        pub fn new(key: &[u8; KEY_LEN], pc: &Nonce16, headset: &Nonce16) -> Self {
            let enc = hmac(key, &[b"PFT2 enc"]);
            Self { aes: Aes256Gcm::new_from_slice(&hmac(&enc, &[pc, headset])).unwrap(), sent: 0 }
        }

        pub fn seal(&mut self, ty: u8, plaintext: &[u8]) -> Vec<u8> {
            self.sent += 1;
            let mut m = vec![0u8; HEADER_LEN + COUNTER_LEN];
            header(&mut m, ty);
            m[HEADER_LEN..].copy_from_slice(&self.sent.to_le_bytes());
            let mut body = plaintext.to_vec();
            let tag =
                self.aes.encrypt_in_place_detached(Nonce::from_slice(&nonce(FROM_HEADSET, self.sent)), &m, &mut body).unwrap();
            m.extend_from_slice(&body);
            m.extend_from_slice(&tag);
            m
        }

        pub fn hello(key: &[u8; KEY_LEN], pc: &Nonce16, headset: &Nonce16) -> Vec<u8> {
            let auth = hmac(key, &[b"PFT2 auth"]);
            let mut m = vec![0u8; HANDSHAKE_LEN];
            header(&mut m, HELLO);
            m[HEADER_LEN..HEADER_LEN + NONCE_LEN].copy_from_slice(pc);
            m[HEADER_LEN + NONCE_LEN..HEADER_LEN + 2 * NONCE_LEN].copy_from_slice(headset);
            let mac = hmac(&auth, &[&m[..HEADER_LEN + 2 * NONCE_LEN]]);
            m[HEADER_LEN + 2 * NONCE_LEN..].copy_from_slice(&mac[..MAC_LEN]);
            m
        }
    }

    #[test]
    fn session_opens_headset_messages_once_and_rejects_tampering() {
        let key = [7u8; KEY_LEN];
        let (pc, hs) = ([1u8; 16], [2u8; 16]);
        let pairing = Pairing::from_key(&key);
        assert_eq!(pairing.verify_hello(&HeadsetSession::hello(&key, &pc, &hs), &pc), Some(hs));
        assert_eq!(pairing.verify_hello(&HeadsetSession::hello(&key, &[9; 16], &hs), &pc), None, "other DISCOVER");
        assert_eq!(pairing.verify_hello(&HeadsetSession::hello(&[8; 32], &pc, &hs), &pc), None, "other key");

        let mut session = pairing.session(&pc, &hs);
        let mut headset = HeadsetSession::new(&key, &pc, &hs);
        let mut out = Vec::new();
        let data = headset.seal(DATA, b"frame bytes");
        assert_eq!(session.open(&data, &mut out, 536), Some(DATA));
        assert_eq!(out, b"frame bytes");
        assert_eq!(session.open(&data, &mut out, 536), None, "replay");
        let mut tampered = headset.seal(PING, b"");
        tampered[HEADER_LEN + COUNTER_LEN] ^= 1;
        assert_eq!(session.open(&tampered, &mut out, 536), None);
        assert_eq!(session.open(&headset.seal(INFO, b"\x0aPICO 4 Pro"), &mut out, 536), Some(INFO));
        assert_eq!(parse_info(&out).as_deref(), Some("PICO 4 Pro"));
    }

    #[test]
    fn info_must_match_its_length_byte() {
        assert_eq!(parse_info(b"\x05A8110").as_deref(), Some("A8110"));
        assert_eq!(parse_info(b"\x05A811"), None);
        assert_eq!(parse_info(b""), None);
        assert_eq!(parse_info(b"\x02\xff\xfe"), None);
    }
}
