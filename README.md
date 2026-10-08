# PicoFacialDataModule

A VRCFaceTracking module that connects to the `picofacialdatadaemon` via UDP.

## Running

1. Download the module ZIP from the releases.
2. In VRCFaceTracking go to Module Registry > Press the plus at the top.
3. Select the ZIP you downloaded.
4. Run the `picofacialdatadaemon`, or install it via Magisk.

### Pairing by key (protocol version 2)

By default the module finds the daemon with an unauthenticated discovery request, and anyone on the network can ask the
headset for tracking data. With a pairing key, the module and the headset prove to each other that they share the key,
find each other again when the PC's address changes, and encrypt the tracking data.

1. Put a Base64 encoded 32-byte random key in `%APPDATA%/PicoFacialData/pairing-key.txt`.
2. Give the same key to the headset side (it is copied over USB, never over the network).
3. Restart VRCFaceTracking. The log shows "Paired by key ... using protocol version 2".

Without the file the module uses the original protocol. The protocol is described in [docs/protocol-v2.md](docs/protocol-v2.md).

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
