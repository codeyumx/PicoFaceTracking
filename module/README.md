# PicoFacialDataModule

A VRCFaceTracking module that connects to the `picofacialdatadaemon` via UDP.

## Running

1. Download the module ZIP from the releases.
2. In VRCFaceTracking go to Module Registry > Press the plus at the top.
3. Select the ZIP you downloaded.
4. Run the `picofacialdatadaemon`, or install it via Magisk.

### Using another headset

VRCFaceTracking gives eye and face tracking to the first module that claims them at start, and switching a module off
on its page does not hand them to another one. So this module waits up to 180 seconds for the headset and claims eye
and face tracking only when it answers. When no headset answers (for example the PICO is off and you use a Steam Frame),
the module stops and leaves them to the other headset's module. To switch back to the PICO, turn on tracking in the
Pico Face Tracking app, then restart VRCFaceTracking. Pairing by code also needs the module running: pair within those
180 seconds or while the headset is connected.

### Pairing by code (protocol version 2)

By default the module finds the daemon with an unauthenticated discovery request, and anyone on the network can ask the
headset for tracking data. Once paired, the module and the headset prove to each other that they share a key, find
each other again when the PC's address changes, and encrypt the tracking data.

1. Start pairing on the headset side (in the Pico Face Tracking app: **Pair by code**).
2. VRCFaceTracking's Output page shows "Pairing code for <headset> (<address>): 123 456".
3. Type the code on the headset. The log shows "Paired with <headset>", and the module switches to the new key at once.

The key is saved in `%APPDATA%/PicoFacialData/pairing-key.txt`; pairing again replaces it. Without the file the module
uses the original protocol. The protocol is described in [docs/protocol-v2.md](docs/protocol-v2.md).

### Settings

Settings can be found at: `%appdata%/VRCFaceTracking/CustomLibs/61ee1324-fd45-42f1-9636-8e28717cf6db/`.


| Setting                              | Description                                                                                                                                                                                                            | Default value |
| ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------- |
| DisableEyeTracking                   | Disables eye tracking in the module only.                                                                                                                                                                              | `false`       |
| DisableFaceTracking                  | Disables face tracking in the module only.                                                                                                                                                                             | `false`       |
|IP| Specifies the IP address of the PICO headset on your network (or outside of it).<br>Skips the multicast discovery process if set.| `null`
| eyePupilDilationEyeOpennessThreshold | This threshold will stop eye dilation tracking below a certain eye openness. | `0.8`         |

### Babble

For those using a Babble face tracker, you can disable the face tracking for this module:

1. In explorer go to:

```shell
%appdata%/VRCFaceTracking/CustomLibs/61ee1324-fd45-42f1-9636-8e28717cf6db/
```

2. Open `PicoFacialDataModule.json` with a text editor of choice.
3. Disable face tracking, like so:

```shell
{
  "DisableEyeTracking": false,
  "DisableFaceTracking": true
}
```
