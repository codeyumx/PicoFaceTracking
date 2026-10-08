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

The key is created on the PC and copied to the headset over USB (adb), so it never crosses the network.

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
