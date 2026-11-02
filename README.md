# Pico Face Tracking

An Android app for the PICO 4 Pro / Enterprise that sends eye and face tracking to
[OSCFT for VRC](https://github.com/codeyumx/oscft_for_vrc) (recommended) or
[VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking) on your PC, without root and without adb
after the first install. It keeps running in the background, so Virtual Desktop (or any other app) can stay in front.

The app is new code, written for Android. Use one of these modules on the PC:

1. **OSCFT for VRC (recommended)**: `Pico4FaceTracking-OSCFT-native.zip`, in [`native/`](native). A native module for
   [OSCFT for VRC](https://github.com/codeyumx/oscft_for_vrc) 0.2.0 or later, for Windows and Linux, faster, with no
   .NET needed. (It does not run in VRCFaceTracking.)
2. **VRCFaceTracking**: `Pico4FaceTracking-VRCFT.zip`, in [`module/`](module). The same module for VRCFaceTracking
   (it also runs in OSCFT for VRC).

# Download and Install

Download the APK and the module from [Releases](https://github.com/codeyumx/PicoFaceTracking/releases). 

Install the .apk on the Pico VR headset. Install the module in OSCFT for VRC (recommended) or VRCFaceTracking.

Everything is free except the additions and future updates like [tracking adjustments](#tracking-adjustments-supporters), which need a supporter licence. Licences are
not available yet: if the app gets enough interest, more settings will follow, and a Patreon or other payment service will give supporters
access to them.

VRCFaceTracking's Pico 4 Face Tracking module only claims eye and face tracking if the headset answers within 5 seconds
of VRCFaceTracking starting. Switch tracking on in the app first; if VRCFaceTracking was already running (its log says
"No PICO headset answered"), restart VRCFaceTracking. The app says this on its settings screen too.

## Panel next to the running VR app

The settings screen declares PICO's activity meta-data `pico.vr.position` = `near` (the same as PICO's own User Guide), so
PICO opens it as a panel next to Virtual Desktop, so the settings and preview can be toggled as you use the tracking data.

## Settings

1. **Face and eye tracking**: on / off (on by default). Off stops the tracking algorithm, which releases the camera.
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
- **Language**: the flag buttons at the top of the Settings tab switch the app (both tabs) and the notification between
  English, Japanese and Simplified Chinese. Until one is picked, the app follows the headset's language. Tracking keeps
  running when the language changes. Expression names in the Test tab's table stay VRCFaceTracking's parameter names.
- PICO's own switches (Settings > LAB, `key_et_enable` / `key_ft_enable` in `Settings.System`) cannot be changed by a
sideloaded app. The app reads them and shows a warning under the eyes and mouth switches while either is off.

## Tracking adjustments (supporters)

- **Quick settings**: tongue out on/off, eye widen maximum, blink strength, smoothing. Switched-off expressions send 0.
- **All expressions**: each of PICO's 52 expressions with on/off, strength (0-200%) and maximum (0-100%). The quick
  settings are shortcuts into these values. Eye blink settings also apply to eye openness, which drives blinking in the
  Pico 4 Face Tracking module.


## Test tab

The **Test** tab next to **Settings** shows what the headset tracks right now, with or without a PC, so you can check
that it sees your eyes and face:

- **Eyes**: a square with dots where you look (big dot: both eyes; blue: left; orange: right; the edges are about 30°
  from straight ahead), and two drawn eyes whose irises follow your gaze and whose eyelids close when you blink or
  close your eyes. These use the values VRCFaceTracking receives from the module.
- **Mouth and jaw**: a drawn lower face that opens its jaw, moves it sideways, smiles, frowns, puckers, puffs its
  cheeks and sticks out its tongue. PICO's face and mouth tracking may not be accurate, so some expressions move
  little, move on their own, or not at all.
- **All parameters**: a slider and number for every eye value (gaze, openness, pupil size) and each of the 52
  expressions, updated with every frame. These are the headset's values before the eyes and mouth switches and the
  tracking adjustments; grey means the headset does not see that part.

The tab runs the headset's eye and face tracking while it is shown, even when tracking is switched off, and stops it
when you leave the tab or close the panel. It shares the running tracking with the connection to the PC, so it does not
interrupt VRCFaceTracking.

## Pairing by code (optional)

Once paired by code, the app answers only a PC that proves it holds the pairing key, from any address, and encrypts the
frames (AES-256-GCM).

1. In the app, press **Pair by code**.
2. The OSC desktop app, with the module installed, shows
   `Pairing code for <headset> (<address>): 123 456` on its output page or settings panel. In
   [OSCFT for VRC](https://github.com/codeyumx/oscft_for_vrc) the code is under **Pairing** in the module's
   settings.
3. Type the code in the app and press **Pair**. Both sides now hold a new random key. The module switches to it and saves it in `%APPDATA%\PicoFacialData\pairing-key.txt`.
**Pair again by code** makes a new key and replaces the old one on both sides. Tracking pauses while the app waits
for a code (at most 5 minutes). The protocol is described in [module/docs/protocol-v2.md](module/docs/protocol-v2.md). Without a key the app speaks the original protocol.

## VRCFaceTracking with Pico4SAFTExtTrackingModule installed too

Both modules can stay installed. Pico4SAFTExtTrackingModule only initialises while PICO Connect, Streaming Assistant or
Business Streaming runs on the PC (`StreamerValidity`), and Pico 4 Face Tracking always initialises, so with Virtual
Desktop the tracking comes through Pico 4 Face Tracking. When you stream with PICO Connect instead, setting 2 keeps
this app from sending a second copy.

## Open core

This repository is the free app, MIT licensed. The tracking adjustments live in a private repository and are compiled
into the release APK only: `PaidFeatures` is the interface they implement, `Paid` finds them by name, and
`app/build.gradle` adds `pro/src` to the sources when it exists. A build from this repository alone has no adjustments;
the settings screen says so.

## Install with adb

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
so other apps on the headset cannot start tracking or redirect it. On the PC, install the Pico 4 Face Tracking module in
VRCFaceTracking: Module Registry, **+**, `Pico4FaceTracking-VRCFT.zip` from the release.

## The native OSCFT module (`native/`)

`native/` is a Rust port of the module in `module/` for OSCFT for VRC's native modules
([spec](https://github.com/codeyumx/oscft_for_vrc/blob/main/spec/native-modules.md)), built on OSCFT's
`fb-module-sdk`. It speaks the same protocols (version 1 and 2, pairing by code), maps the same expressions and uses
the same pairing key file (`PicoFacialData/pairing-key.txt` in the user's config folder: `%APPDATA%` on Windows,
`~/.config` on Linux), so switching between the two keeps the pairing. It has the same `ModuleId`, so installing it
in OSCFT replaces the VRCFaceTracking version.

Clone with `git clone --recursive` (the module builds against the `module/VRCFaceTracking` and `module/oscft_for_vrc`
submodules).
