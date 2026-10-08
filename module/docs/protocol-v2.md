# Pico facial data protocol, version 2 (paired)

Version 1 is PicoFacialDataDaemon's protocol: the PC sends `DISCOVER_DAEMON`, the headset streams plain frames to
whoever sent it, `MARCO`/`POLO` keep the session alive, `STOP` ends it. Anyone on the network can request the data,
and the only way to restrict it is the PC's IP address, which breaks when the address changes.

Version 2 adds a shared pairing key. The headset answers any PC that proves it holds the key, from any address, and
the frames are encrypted. Without a key both sides keep speaking version 1.

## Key

- `K`: 32 random bytes, stored as standard Base64 text.
  - PC: `%APPDATA%\PicoFacialData\pairing-key.txt`
  - Headset: in the app's private settings.
- `K_auth = HMAC-SHA256(K, "PFT2 auth")`, `K_enc = HMAC-SHA256(K, "PFT2 enc")` (labels are ASCII, no terminator).
- `mac(x)` = the first 16 bytes of `HMAC-SHA256(K_auth, x)`.

The key is created by [pairing by code](#pairing-by-code): both sides derive it, and it never crosses the network.

## Messages

UDP port 9030 on both sides, as in version 1. Every message starts with the 5-byte header
`"PFT2"` (ASCII) followed by a type byte. Integers are little-endian.

### Handshake

| Type | Name | Direction | Body after the header |
|---|---|---|---|
| 1 | DISCOVER | PC to headset, multicast `239.255.255.250:9030` and/or unicast | `nP` (16 random bytes), `mac(header ‖ nP)` |
| 2 | HELLO | headset to the DISCOVER sender, from the socket it will stream from | `nP`, `nH` (16 random bytes), `mac(header ‖ nP ‖ nH)` |
| 3 | START | PC to the HELLO sender | `nP`, `nH`, `mac(header ‖ nP ‖ nH)` |

- The PC sends a fresh `nP` with every DISCOVER and accepts only a HELLO for its latest `nP`. It sends START to,
  and afterwards only accepts session messages from, the address and port the HELLO came from.
- The headset accepts a START only from the address the HELLO went to, for the `nP`/`nH` it sent, within 2 seconds.
  A new valid DISCOVER replaces a pending handshake.
- The headset only answers DISCOVER from local addresses (private or link-local).
- A recorded DISCOVER can make the headset send a HELLO, but without the key nobody can produce the START.

Session key: `K_s = HMAC-SHA256(K_enc, nP ‖ nH)`, 32 bytes, AES-256.

### Session

| Type | Name | Direction | Plaintext |
|---|---|---|---|
| 4 | DATA | headset to PC | one tracking frame (version 1 payload: 384 bytes face + 152 bytes eye) |
| 5 | PING | headset to PC | empty |
| 6 | PONG | PC to headset | empty |
| 7 | STOP | PC to headset | empty |

Body: `counter` (uint64), then `AES-256-GCM(K_s)` ciphertext and 16-byte tag.

- Nonce (12 bytes): direction tag (`"HMD\0"` from the headset, `"PC\0\0"` from the PC) followed by `counter`.
- Associated data: header ‖ counter.
- Each direction counts from 1 per session. A receiver drops messages whose counter is not higher than the last one
  it accepted, and messages that fail authentication.

Timing is unchanged from version 1: the headset pings every 25 seconds while streaming and every second while no
data flows; it ends the session when no valid PONG arrives for 25 seconds or on STOP. The PC starts discovery again
when nothing valid arrives for 2 seconds.

## Downgrade

A side that has a key speaks only version 2. Version 1 messages are ignored, so an attacker cannot fall back to the
unauthenticated protocol.

## Pairing by code

The PC module shows a 6-digit code in VRCFaceTracking's log; the user types it on the headset. Both sides then run
SPAKE2 ([RFC 9382](https://www.rfc-editor.org/rfc/rfc9382)) with the code as password and get the same new key `K`.
The code never crosses the network. Someone who does not know it, whether pretending to be the PC or the headset,
gets one guess per code (1 in a million); the PC shows a new code after each wrong one, pauses 10 seconds, and stops
after 5 wrong codes until VRCFaceTracking restarts.

The headset only listens for pairing while the user has asked to pair (at most 5 minutes) and uses UDP port 9030
for it, so tracking stops meanwhile. The PC asks for pairing headsets every 2 seconds from its own socket, the same way
it discovers the headset for tracking (multicast `239.255.255.250:9030`, or the configured headset address).

### Group

- `p`: the 2048-bit MODP prime of [RFC 3526](https://www.rfc-editor.org/rfc/rfc3526), section 3; `q = (p - 1) / 2`;
  generator `g = 2`, which has order `q`.
- Elements are encoded as 256 big-endian bytes. A received element `e` is valid when `1 < e < p - 1` and
  `e^q mod p = 1`; invalid ones are ignored.
- `M` and `N`: `hashToGroup("PFT2 pairing M")` and `hashToGroup("PFT2 pairing N")`, where
  `hashToGroup(label) = (int(SHA256(label ‖ 0x00) ‖ … ‖ SHA256(label ‖ 0x08)) mod p)^2 mod p`, labels in ASCII.
- `h = SHA256("PFT2 pairing code " ‖ code)`, the code as 6 ASCII digits; `w = int(h) mod q`.
- Secret exponents `x` (PC) and `y` (headset): 32 random bytes as an integer, mod `q`, not 0.

### Messages

Same 5-byte header as above. `nH` and `nP` are 16 random bytes: `nH` per pairing attempt on the headset, `nP`
per code on the PC. Names are a length byte (at most 64) followed by UTF-8 text, for display only.

| Type | Name | Direction | Body after the header |
|---|---|---|---|
| 8 | PAIR_DISCOVER | PC to headset, multicast or unicast | empty |
| 9 | PAIR_OFFER | headset to the sender of PAIR_DISCOVER | `nH`, headset name |
| 10 | PAIR_START | PC to headset | `nH`, `nP`, `T = g^x · M^w`, PC name |
| 11 | PAIR_RESPONSE | headset to PC | `nH`, `nP`, `S = g^y · N^w`, `cH` |
| 12 | PAIR_CONFIRM | PC to headset | `nH`, `nP`, `cP` |
| 13 | PAIR_REJECT | PC to headset | `nH`, `nP` |

- Shared element: PC `Z = (S · N^(q - w))^x`, headset `Z = (T · M^(q - w))^y` (both `g^xy`).
- `TT = SHA256("PFT2 pairing" ‖ nH ‖ nP ‖ T ‖ S ‖ Z ‖ h)`
- `cH = HMAC-SHA256(HMAC-SHA256(TT, "PFT2 confirm HMD"), nH ‖ nP)`,
  `cP = HMAC-SHA256(HMAC-SHA256(TT, "PFT2 confirm PC"), nH ‖ nP)`
- New pairing key: `K = HMAC-SHA256(TT, "PFT2 pairing key")`

### Flow

1. The headset answers every PAIR_DISCOVER with PAIR_OFFER for its current `nH`.
2. The PC makes a code for the first headset that offers (one headset at a time; the same headset may start over
   with a new `nH`), logs it with the headset's name and address, and sends PAIR_START, again for every repeated
   offer. The headset lists every PC that sent a PAIR_START.
3. The user picks the PC and types the code; the headset sends PAIR_RESPONSE every second, for up to 6 seconds.
4. The PC checks `cH`. Right code: it saves `K`, switches to it at once and sends PAIR_CONFIRM (again for a repeated
   PAIR_RESPONSE). Wrong code: PAIR_REJECT; the headset starts a new attempt with a new `nH`, and the PC shows a new
   code for it.
5. The headset checks `cP` and saves `K`. PAIR_REJECT is not authenticated, so it only ends the attempt.

If the PAIR_CONFIRM is lost for good, the PC has the new key and the headset the old one: pair again.

### Test vectors

Code `123456`, wrong code `123457`, `x = SHA256("vector x")`, `y = SHA256("vector y")`, `nP` = 16 × `0x11`,
`nH` = 16 × `0x22`, PC name `TEST-PC`:

- PAIR_START: `504654320a22222222222222222222222222222222111111111111111111111111111111113d04d2e15cd211f9c29067e23f48e6267d81a47638d5264f5317679261da1c01f5f53fcd84a657d561cb8149b55b481d4f81b105a265647c325382358c6aa089826e8c35f561e6fd7bf092b50645b6ef6926ef9faf93aab74e1c63a46f79b757ac419beca68455740d54c7a80b0f96873c8cd57701f8ce466c984741fcd2c0b635afdf514b1ab3a3769aeb5b1106285c145b6a68e4f5c93d9cc7cca037eba2d51da85347a5b344470f507d635b9fee717f527b0cfa26f65a431d3536cd2f58be5e732edbe2d412d8c5617f7c7f8c85aa8bf84988259f4d1898e4d631f01fe3905545ca5ae85c5e601c840b55e9be4abb597a8059fc8f70d2307a50b72a98378907544553542d5043`
- PAIR_RESPONSE: `504654320b2222222222222222222222222222222211111111111111111111111111111111c5aa39b4335e1c56817f55797aaf39be57e6ca0da566a1179fcd7c90597c6c5cf7a9e63c2895cdd399ee72ad439bbe5ea32e01959ffb3b4c1ee3eeec7e5ccfba34c64642511e046e208b9b1cfa0e8263165a9804ed7cffb6cb7be04878656d4282f52b5d3f02de75761d7b093535d2be0511a9f1802a18c2cf3af5e1cc19ade749faae777b03f2ebc1ae9498a4bad0d654d4e580175598a0c7f446205bfc24df70134133b34dc1dab59a1bbb3009d812a96852d109f8b7be7c77c5b3785892927cb886178687d0fd0502e992a37df598c6f9ed177917a3a1829bf71893ce35c8a30e83cff945c1fbae37c643a347a606a48d19ed14f88b7ae33d7aefba9f1cc183d158acff7fce1d54931e18af948cc07015e738a20cbcbaa7b6edfe6c6858e7`
- PAIR_CONFIRM: `504654320c2222222222222222222222222222222211111111111111111111111111111111fb3d5f2f01ff9509a3818980cfd33d78fcffe7030d56af9d99dce833d0e93d47`
- `K` (Base64): `9kvcbV9h9d4NGThcdJKY0JAA8B33pShjBIbEs71mfHo=`
- PAIR_RESPONSE with the wrong code: `504654320b2222222222222222222222222222222211111111111111111111111111111111a2492fbfca04798b622bd5376db935f1cee6b0c68cffa448aecc66ec1d14acfd8c51ba57dec1820656275708b6d47c4596fcbd6ff3eb9c49da8e5e1a6f0f6f55bae294a1be3682a6b62a2e9e0eea0b5fa7292282e8ecb58eb3547ca9eddb975a3a8dcf21ddc9a5bf6875592f5e56e10807503f12ecf44fe3c1c9509049c5032d2faef7f39c2c301c5eed12e1b69f0ece9d126849c010dd35e8728d9ec588fe0d37ed162e442e2757dc5a918aca23b07d1280df34acb7bf56c7363b3fd598a6d62f753a2af55edc7e11ad261d1dcd669e2348df462d70c938794c570f12c5f97ec77a6ef7c52c60e874d8a8a534f242c699bb9ab2ecb5c658a2f2072c608504754e4d0a8c436818cf2460cde424f091c98adebd31b47b38e1f61663142783be57`
