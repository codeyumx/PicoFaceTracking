# Pico 4 Face Tracking (VRCFaceTracking module)

A VRCFaceTracking module for the PICO 4 Pro and PICO 4 Enterprise, the PICO headsets with eye and face tracking cameras.
It receives eye and face tracking over the local network from the Pico Face Tracking app on the headset. It is based on
thoricelli's Pico Facial Data Module and also works with thoricelli's `picofacialdatadaemon`.

## Running

1. Download `Pico4FaceTracking-VRCFT.zip` from the releases.
2. In VRCFaceTracking go to Module Registry > Press the plus at the top.
3. Select the ZIP you downloaded.
4. On the headset, turn tracking on in the Pico Face Tracking app.

The module claims eye and face tracking only when a headset answers within 45 seconds of VRCFaceTracking starting
(`HeadsetWaitSeconds`; also a headset waiting on its pairing screen). It claims them as soon as the headset answers, and
modules for other headsets do not wait for it. If tracking was still off after the wait, the log says "No PICO headset
answered"; turn it on and restart VRCFaceTracking. Set `ClaimWithoutHeadset` to claim them regardless, as older versions
did.

### Switching to another headset

VRCFaceTracking gives eye and face tracking to the first module that claims them when it starts, and does not move
them to another module later. With the PICO off or disconnected when VRCFaceTracking starts, this module steps aside,
so a module for another headset (for example SteamLink for the Steam Frame) gets them. Switching this module off on
VRCFaceTracking's home page stops the PICO's tracking, but the other module only takes over after a restart of
VRCFaceTracking. If the PICO stops sending while this module has the tracking, the face and eyes go neutral instead of
freezing on the last frame.

### Pairing by code (protocol version 2)

By default the module finds the headset with an unauthenticated discovery request, and anyone on the network can ask the
headset for tracking data. Once paired, the module and the headset prove to each other that they share a key, find
each other again when the PC's address changes, and encrypt the tracking data.

1. Start pairing on the headset side (in the Pico Face Tracking app: **Pair by code**).
2. VRCFaceTracking's Output page shows "Pairing code for <headset> (<address>): 123 456".
3. Type the code on the headset. The log shows "Paired with <headset>", and the module switches to the new key at once.

The key is saved in `%APPDATA%/PicoFacialData/pairing-key.txt`; pairing again replaces it. Without the file the module
uses the original protocol. The protocol is described in [docs/protocol-v2.md](docs/protocol-v2.md).

### Settings

Settings can be found at: `%appdata%/VRCFaceTracking/CustomLibs/168d44d5-bbb7-4e86-8ae5-732047311d43/`.


| Setting                              | Description                                                                                                                                                                                                            | Default value |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| DisableEyeTracking                   | Disables eye tracking in the module only.                                                                                                                                                                              | `false`       |
| DisableFaceTracking                  | Disables face tracking in the module only.                                                                                                                                                                             | `false`       |
|IP| Specifies the IP address of the PICO headset on your network (or outside of it).<br>Skips the multicast discovery process if set.| `null`
| ClaimWithoutHeadset                  | Claims eye and face tracking even when no headset answers while VRCFaceTracking starts, so no other module gets them.                                                                                                 | `false`       |
| HeadsetWaitSeconds                   | How long VRCFaceTracking's start waits for the headset before leaving eye and face tracking to other modules. At most `50`: VRCFaceTracking stops a module that takes over 60 seconds to start. `0` gives up at once. | `45`          |
| eyePupilDilationEyeOpennessThreshold | This threshold will stop eye dilation tracking below a certain eye openness. | `0.8`         |

### Babble

For those using a Babble face tracker, you can disable the face tracking for this module:

1. In explorer go to:

```shell
%appdata%/VRCFaceTracking/CustomLibs/168d44d5-bbb7-4e86-8ae5-732047311d43/
```

2. Open `PicoFacialDataModule.json` with a text editor of choice.
3. Disable face tracking, like so:

```shell
{
  "DisableEyeTracking": false,
  "DisableFaceTracking": true
}
```
