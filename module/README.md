# Pico 4 Face Tracking (VRCFaceTracking module)

A VRCFaceTracking module for the PICO 4 Pro and PICO 4 Enterprise, the PICO headsets with eye and face tracking cameras.
It receives eye and face tracking over the local network from the Pico Face Tracking app on the headset. It is based on
thoricelli's Pico Facial Data Module and also works with thoricelli's `picofacialdatadaemon`.

## Running

1. Download `Pico4FaceTracking-VRCFT.zip` from the releases.
2. In VRCFaceTracking go to Module Registry > Press the plus at the top.
3. Select the ZIP you downloaded.
4. On the headset, turn tracking on in the Pico Face Tracking app.

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
