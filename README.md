# Pico Face Tracking

An Android app for the PICO 4 Pro / Enterprise that sends eye and face tracking to
[VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking) on your PC, without root and without adb
after the first install. It keeps running in the background, so Virtual Desktop (or any other app) can stay in front.

The app is new code, written for Android. It speaks the network protocol of
[thoricelli/PicoFacialDataDaemon](https://github.com/thoricelli/PicoFacialDataDaemon) and builds on what thoricelli
worked out about PICO's eye tracking service (the daemon and
[PICO-documentation](https://github.com/thoricelli/PICO-documentation)); none of the daemon's code is included.
It works with either VRCFaceTracking module:

- [Pico Facial Data Module](https://github.com/thoricelli/PicoFacialDataModule) 0.3 by thoricelli: original protocol,
  the PC is recognised by its address.
- [Pico Facial Data Module (paired)](https://github.com/codeyumx/PicoFacialDataModule/releases/tag/v0.5-paired.2):
  pairing by code (protocol version 2, below).

Download the APK from [Releases](https://github.com/codeyumx/PicoFaceTracking/releases). Everything is free except the
[tracking adjustments](#tracking-adjustments-supporters), which unlock with a supporter licence from Patreon.

## Panel next to the running VR app

The settings screen declares PICO's activity meta-data `pico.vr.position` = `near` (the same as PICO's own User Guide), so
PICO opens it as a panel next to Virtual Desktop instead of switching apps, which would close Virtual Desktop.

## Settings

1. **Face and eye tracking**: on / off. Off stops the tracking algorithm, which releases the camera.
   The notification also has a **Turn off** button.
   **Eyes and brows** and **Mouth, jaw, cheeks and tongue** (both on by default, free): untick one to send that part
   as neutral while the other keeps tracking, for example eyes only. Eyes off sends open eyes looking straight ahead;
   mouth off sends 0 for every mouth, jaw, cheek, nose and tongue expression. Changes apply at once.
2. **Pause while another app uses eye and face tracking** (default on): before starting, the app asks the tracking
   service whether tracking already runs (`GetAlgorithmResult` `et_running` / `ft_running`). If PICO Connect or another
   app uses it, the app waits instead of sending the same face to VRCFaceTracking a second time.
- **Paired PC**: the only PC that may receive the tracking data. Leave it empty and the app pairs with the first PC on
  the local network that runs VRCFaceTracking (its discovery request), then remembers it. Clear it to pair again.
- **Start when the headset turns on**: turns tracking back on after a restart if it was on before.
  PICO OS 5.9 can stop sideloaded apps from starting by broadcast (`ProcessIntercept`: "It is forbidden to start a 3rd
  process by broadcast"); opening the app also turns tracking back on if it was on.

## Tracking adjustments (supporters)

Applied on the headset to every frame before it is sent, and live while tracking runs. They are in the release APK and
unlock with a supporter licence file from Patreon (see [Open core](#open-core)).

- **Quick settings**: tongue out on/off, eye widen maximum, blink strength, smoothing. Switched-off expressions send 0.
- **All expressions**: each of PICO's 52 expressions with on/off, strength (0-200%) and maximum (0-100%). The quick
  settings are shortcuts into these values. Eye blink settings also apply to eye openness, which drives blinking in
  Pico Facial Data Module.

PICO's own switches (Settings > LAB, `key_et_enable` / `key_ft_enable`) cannot be changed by a sideloaded app.

## Pairing by code (protocol version 2, optional)

Once paired by code, the app answers only a PC that proves it holds the pairing key, from any address, and encrypts the
frames (AES-256-GCM). No cable and no PC software besides VRCFaceTracking:

1. In the app, press **Pair by code**.
2. VRCFaceTracking, with Pico Facial Data Module (paired), shows
   `Pairing code for <headset> (<address>): 123 456` on its Output page.
3. Type the code in the app and press **Pair**. Both sides now hold a new random key; the module switches to it at
   once and saves it in `%APPDATA%\PicoFacialData\pairing-key.txt`.

The code never crosses the network: SPAKE2 turns it into the key, and someone who does not know it gets one guess per
code. **Pair again by code** makes a new key and replaces the old one on both sides. Tracking pauses while the app waits
for a code (at most 5 minutes). The protocol is described in [docs/protocol-v2.md](docs/protocol-v2.md).

To turn it off, press **Remove pairing key**: the app pairs by address again and works with thoricelli's original
module. Without a key the app speaks the original protocol.

## VRCFaceTracking with Pico4SAFTExtTrackingModule installed too

Both modules can stay installed. Pico4SAFTExtTrackingModule only initialises while PICO Connect, Streaming Assistant or
Business Streaming runs on the PC (`StreamerValidity`), and Pico Facial Data Module always initialises, so with Virtual
Desktop the tracking comes through Pico Facial Data Module. When you stream with PICO Connect instead, setting 2 keeps
this app from sending a second copy.

## Open core

This repository is the free app, MIT licensed. The tracking adjustments live in a private repository and are compiled
into the release APK only: `PaidFeatures` is the interface they implement, `Paid` finds them by name, and
`app/build.gradle` adds `pro/src` to the sources when it exists. A build from this repository alone has no adjustments;
the settings screen says so.

A licence is a small text file signed with the author's private key (ECDSA P-256, SHA-256); `Licence.java` holds the
public key and checks it on the headset, offline:

```
Pico Face Tracking licence
licensee: Patreon supporters
expires: 2026-11-30
signature: MEUCIQ...
```

`expires` is optional (last valid day). Import it in the app (**Import licence file**), let `Install face tracking app.bat`
send it, or over adb (Base64 of the file; empty removes it):

```
adb shell am broadcast -n io.github.codeyumx.picofacetracking/.AdbControlReceiver --es licence <Base64 of the file>
```

The reply includes `licence=none|valid|expired` and `adjustments=in-build|not-in-build`.

## Install

```
adb install -r PicoFaceTracking.apk
adb shell pm grant io.github.codeyumx.picofacetracking com.picovr.permission.EYE_TRACKING
adb shell pm grant io.github.codeyumx.picofacetracking com.picovr.permission.FACE_TRACKING
```

Then set your PC's address and switch tracking on, either in the app or over adb:

```
adb shell am broadcast -n io.github.codeyumx.picofacetracking/.AdbControlReceiver --es pc_address 192.168.1.20 --ez tracking true
```

An empty `pc_address` forgets the paired PC.

`AdbControlReceiver` is protected by `android.permission.DUMP`, which the adb shell holds and normal apps cannot get,
so other apps on the headset cannot start tracking or redirect it. On the PC, install the Pico Facial Data Module in
VRCFaceTracking.

## How it works

The app talks to PICO's `pxreyetrackingservice` over binder (`StartAlgorithm`, `GetTrackingDataSharedMemory`,
`StopAlgorithm`), maps the shared ring buffers read-only, and streams the newest results over UDP port 9030 using the
daemon's protocol (`DISCOVER_DAEMON`, `MARCO`/`POLO`, `STOP`). Each packet is 384 bytes of face data followed by
152 bytes of eye data, the layout Pico Facial Data Module 0.3 accepts.

Without a pairing key the data is not encrypted; use a network you trust. With a pairing key, see above.

## Building

GitHub Actions builds the free app on every push to `main` and on pull requests. A `v*` tag builds the release APK with
the paid features (checked out from the private repository with the `PRO_DEPLOY_KEY` deploy key) and publishes it as a
GitHub release. Signing uses two repository secrets: `SIGNING_KEYSTORE_BASE64` (a PKCS#12 keystore with key alias `picofacetracking`) and `SIGNING_PASSWORD`.
