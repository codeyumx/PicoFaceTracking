//! Pairing by code, PC side (`module/docs/protocol-v2.md`, "Pairing by code"): SPAKE2 in the 2048-bit MODP group of
//! RFC 3526 turns a 6-digit code into a new pairing key on both sides. Port of the .NET module's `PairingExchange`.

use crate::pairing::{HEADER_LEN, KEY_LEN, NONCE_LEN, Nonce16, header, hmac, type_of};
use num_bigint::BigUint;
use rand::Rng;
use sha2::{Digest, Sha256};
use std::sync::LazyLock;
use subtle::ConstantTimeEq;

pub const DISCOVER: u8 = 8;
pub const OFFER: u8 = 9;
pub const START: u8 = 10;
pub const RESPONSE: u8 = 11;
pub const CONFIRM: u8 = 12;
pub const REJECT: u8 = 13;

const ELEMENT_LEN: usize = 256;
const CONFIRMATION_LEN: usize = 32;
const MAX_NAME_LEN: usize = 64;
const RESPONSE_LEN: usize = HEADER_LEN + 2 * NONCE_LEN + ELEMENT_LEN + CONFIRMATION_LEN;

const P_HEX: &str = "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74\
020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374F\
E1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE38\
6BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48\
361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB9ED52907\
7096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C18\
0E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA95\
6AE515D2261898FA051015728E5A8AACAA68FFFFFFFFFFFFFFFF";

struct Group {
    p: BigUint,
    q: BigUint,
    m: BigUint,
    n: BigUint,
}

static GROUP: LazyLock<Group> = LazyLock::new(|| {
    let p = BigUint::parse_bytes(P_HEX.as_bytes(), 16).expect("valid prime");
    let q = &p >> 1u32;
    let m = hash_to_group(&p, b"PFT2 pairing M");
    let n = hash_to_group(&p, b"PFT2 pairing N");
    Group { p, q, m, n }
});

fn group() -> &'static Group {
    &GROUP
}

fn sha256(parts: &[&[u8]]) -> [u8; 32] {
    let mut h = Sha256::new();
    for part in parts {
        h.update(part);
    }
    h.finalize().into()
}

/// An element of the order-q subgroup whose discrete logarithm nobody knows.
fn hash_to_group(p: &BigUint, label: &[u8]) -> BigUint {
    let wide: Vec<u8> = (0u8..9).flat_map(|i| sha256(&[label, &[i]])).collect();
    let v = BigUint::from_bytes_be(&wide) % p;
    &v * &v % p
}

/// A group element as 256 big-endian bytes.
fn to_element(v: &BigUint) -> [u8; ELEMENT_LEN] {
    let bytes = v.to_bytes_be();
    let mut e = [0u8; ELEMENT_LEN];
    e[ELEMENT_LEN - bytes.len()..].copy_from_slice(&bytes);
    e
}

fn is_group_element(g: &Group, v: &BigUint) -> bool {
    let one = BigUint::from(1u32);
    *v > one && *v < &g.p - &one && v.modpow(&g.q, &g.p) == one
}

/// UTF-8 name cut to 64 bytes without splitting a character.
fn name_bytes(text: &str) -> &[u8] {
    let bytes = text.as_bytes();
    let mut len = bytes.len().min(MAX_NAME_LEN);
    while len > 0 && len < bytes.len() && bytes[len] & 0xc0 == 0x80 {
        len -= 1;
    }
    &bytes[..len]
}

pub fn discover_message() -> Vec<u8> {
    let mut m = vec![0u8; HEADER_LEN];
    header(&mut m, DISCOVER);
    m
}

/// A headset's OFFER: the nonce of its pairing attempt and its name.
pub fn parse_offer(message: &[u8]) -> Option<(Nonce16, String)> {
    let fixed = HEADER_LEN + NONCE_LEN + 1;
    if message.len() < fixed || type_of(message) != Some(OFFER) {
        return None;
    }
    let name_len = usize::from(message[fixed - 1]);
    if name_len > MAX_NAME_LEN || message.len() != fixed + name_len {
        return None;
    }
    let nonce = message[HEADER_LEN..HEADER_LEN + NONCE_LEN].try_into().expect("16 bytes");
    Some((nonce, String::from_utf8_lossy(&message[fixed..]).into_owned()))
}

#[derive(Debug, PartialEq, Eq)]
pub enum Outcome {
    /// The message does not belong to this attempt.
    NotForThis,
    /// Wrong code: send the REJECT.
    WrongCode(Vec<u8>),
    /// Right code: save the key and send the CONFIRM.
    Paired { key: [u8; KEY_LEN], confirm: Vec<u8> },
}

/// One pairing attempt with one headset.
pub struct Exchange {
    headset_nonce: Nonce16,
    pc_nonce: Nonce16,
    code_hash: [u8; 32],
    w: BigUint,
    x: BigUint,
    element: [u8; ELEMENT_LEN],
    /// The six digits to show.
    pub code: String,
    /// The START for the headset, repeated until it answers.
    pub start_message: Vec<u8>,
}

impl Exchange {
    pub fn new(headset_nonce: Nonce16, pc_name: &str) -> Self {
        let mut rng = rand::rng();
        let code = format!("{:06}", rng.random_range(0..1_000_000u32));
        let pc_nonce: Nonce16 = rng.random();
        let q = &group().q;
        let x = loop {
            let x = BigUint::from_bytes_be(&rng.random::<[u8; 32]>()) % q;
            if x != BigUint::ZERO {
                break x;
            }
        };
        Self::with(headset_nonce, pc_name, code, pc_nonce, x)
    }

    fn with(headset_nonce: Nonce16, pc_name: &str, code: String, pc_nonce: Nonce16, x: BigUint) -> Self {
        let g = group();
        let code_hash = sha256(&[b"PFT2 pairing code ", code.as_bytes()]);
        let w = BigUint::from_bytes_be(&code_hash) % &g.q;
        let t = BigUint::from(2u32).modpow(&x, &g.p) * g.m.modpow(&w, &g.p) % &g.p;
        let element = to_element(&t);

        let name = name_bytes(pc_name);
        let mut start = vec![0u8; HEADER_LEN];
        header(&mut start, START);
        start.extend_from_slice(&headset_nonce);
        start.extend_from_slice(&pc_nonce);
        start.extend_from_slice(&element);
        start.push(name.len() as u8);
        start.extend_from_slice(name);
        Self { headset_nonce, pc_nonce, code_hash, w, x, element, code, start_message: start }
    }

    pub fn headset_nonce(&self) -> &Nonce16 {
        &self.headset_nonce
    }

    /// Checks the headset's RESPONSE.
    pub fn finish(&self, response: &[u8]) -> Outcome {
        let n = NONCE_LEN;
        if response.len() != RESPONSE_LEN
            || type_of(response) != Some(RESPONSE)
            || response[HEADER_LEN..HEADER_LEN + n] != self.headset_nonce
            || response[HEADER_LEN + n..HEADER_LEN + 2 * n] != self.pc_nonce
        {
            return Outcome::NotForThis;
        }
        let g = group();
        let s = &response[HEADER_LEN + 2 * n..HEADER_LEN + 2 * n + ELEMENT_LEN];
        let s_value = BigUint::from_bytes_be(s);
        if !is_group_element(g, &s_value) {
            return Outcome::NotForThis;
        }
        let exponent = (&g.q - &self.w) % &g.q;
        let shared = to_element(&(s_value * g.n.modpow(&exponent, &g.p) % &g.p).modpow(&self.x, &g.p));
        let transcript = sha256(&[
            b"PFT2 pairing",
            &self.headset_nonce,
            &self.pc_nonce,
            &self.element,
            s,
            &shared,
            &self.code_hash,
        ]);
        let nonces = [self.headset_nonce, self.pc_nonce].concat();
        let headset_confirmation = hmac(&hmac(&transcript, &[b"PFT2 confirm HMD"]), &[&nonces]);

        let mut reply = vec![0u8; HEADER_LEN];
        reply.extend_from_slice(&nonces);
        if !bool::from(headset_confirmation.ct_eq(&response[RESPONSE_LEN - CONFIRMATION_LEN..])) {
            header(&mut reply, REJECT);
            return Outcome::WrongCode(reply);
        }
        header(&mut reply, CONFIRM);
        reply.extend_from_slice(&hmac(&hmac(&transcript, &[b"PFT2 confirm PC"]), &[&nonces]));
        Outcome::Paired { key: hmac(&transcript, &[b"PFT2 pairing key"]), confirm: reply }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::Engine as _;

    // Vectors from module/docs/protocol-v2.md, also checked by the app and the .NET module.
    const START_HEX: &str = "504654320a22222222222222222222222222222222111111111111111111111111111111113d04d2e15cd211f9c29067e23f48e6267d81a47638d5264f5317679261da1c01f5f53fcd84a657d561cb8149b55b481d4f81b105a265647c325382358c6aa089826e8c35f561e6fd7bf092b50645b6ef6926ef9faf93aab74e1c63a46f79b757ac419beca68455740d54c7a80b0f96873c8cd57701f8ce466c984741fcd2c0b635afdf514b1ab3a3769aeb5b1106285c145b6a68e4f5c93d9cc7cca037eba2d51da85347a5b344470f507d635b9fee717f527b0cfa26f65a431d3536cd2f58be5e732edbe2d412d8c5617f7c7f8c85aa8bf84988259f4d1898e4d631f01fe3905545ca5ae85c5e601c840b55e9be4abb597a8059fc8f70d2307a50b72a98378907544553542d5043";
    const RESPONSE_HEX: &str = "504654320b2222222222222222222222222222222211111111111111111111111111111111c5aa39b4335e1c56817f55797aaf39be57e6ca0da566a1179fcd7c90597c6c5cf7a9e63c2895cdd399ee72ad439bbe5ea32e01959ffb3b4c1ee3eeec7e5ccfba34c64642511e046e208b9b1cfa0e8263165a9804ed7cffb6cb7be04878656d4282f52b5d3f02de75761d7b093535d2be0511a9f1802a18c2cf3af5e1cc19ade749faae777b03f2ebc1ae9498a4bad0d654d4e580175598a0c7f446205bfc24df70134133b34dc1dab59a1bbb3009d812a96852d109f8b7be7c77c5b3785892927cb886178687d0fd0502e992a37df598c6f9ed177917a3a1829bf71893ce35c8a30e83cff945c1fbae37c643a347a606a48d19ed14f88b7ae33d7aefba9f1cc183d158acff7fce1d54931e18af948cc07015e738a20cbcbaa7b6edfe6c6858e7";
    const CONFIRM_HEX: &str = "504654320c2222222222222222222222222222222211111111111111111111111111111111fb3d5f2f01ff9509a3818980cfd33d78fcffe7030d56af9d99dce833d0e93d47";
    const KEY_BASE64: &str = "9kvcbV9h9d4NGThcdJKY0JAA8B33pShjBIbEs71mfHo=";
    const WRONG_RESPONSE_HEX: &str = "504654320b2222222222222222222222222222222211111111111111111111111111111111a2492fbfca04798b622bd5376db935f1cee6b0c68cffa448aecc66ec1d14acfd8c51ba57dec1820656275708b6d47c4596fcbd6ff3eb9c49da8e5e1a6f0f6f55bae294a1be3682a6b62a2e9e0eea0b5fa7292282e8ecb58eb3547ca9eddb975a3a8dcf21ddc9a5bf6875592f5e56e10807503f12ecf44fe3c1c9509049c5032d2faef7f39c2c301c5eed12e1b69f0ece9d126849c010dd35e8728d9ec588fe0d37ed162e442e2757dc5a918aca23b07d1280df34acb7bf56c7363b3fd598a6d62f753a2af55edc7e11ad261d1dcd669e2348df462d70c938794c570f12c5f97ec77a6ef7c52c60e874d8a8a534f242c699bb9ab2ecb5c658a2f2072c608504754e4d0a8c436818cf2460cde424f091c98adebd31b47b38e1f61663142783be57";

    fn exchange() -> Exchange {
        let x = BigUint::from_bytes_be(&sha256(&[b"vector x"]));
        Exchange::with([0x22; 16], "TEST-PC", "123456".into(), [0x11; 16], x)
    }

    #[test]
    fn start_matches_the_headsets_vector() {
        assert_eq!(hex::encode(&exchange().start_message), START_HEX);
    }

    #[test]
    fn right_code_gives_the_headsets_key() {
        let Outcome::Paired { key, confirm } = exchange().finish(&hex::decode(RESPONSE_HEX).unwrap()) else {
            panic!("not paired")
        };
        assert_eq!(hex::encode(confirm), CONFIRM_HEX);
        assert_eq!(base64::engine::general_purpose::STANDARD.encode(key), KEY_BASE64);
    }

    #[test]
    fn wrong_code_is_rejected_without_a_key() {
        let reply = [&b"PFT2\x0d"[..], &[0x22; 16], &[0x11; 16]].concat();
        assert_eq!(exchange().finish(&hex::decode(WRONG_RESPONSE_HEX).unwrap()), Outcome::WrongCode(reply));
    }

    #[test]
    fn response_for_another_attempt_or_invalid_element_is_ignored() {
        let mut other = hex::decode(RESPONSE_HEX).unwrap();
        other[5] ^= 1;
        assert_eq!(exchange().finish(&other), Outcome::NotForThis);
        let mut identity = hex::decode(RESPONSE_HEX).unwrap();
        identity[37..37 + 256].fill(0);
        identity[37 + 255] = 1;
        assert_eq!(exchange().finish(&identity), Outcome::NotForThis);
    }

    #[test]
    fn offer_is_read() {
        let offer = [&b"PFT2\x09"[..], &[0x22; 16], &[5], b"A8110"].concat();
        assert_eq!(parse_offer(&offer), Some(([0x22; 16], "A8110".into())));
        assert_eq!(parse_offer(&offer[..offer.len() - 1]), None);
    }
}
