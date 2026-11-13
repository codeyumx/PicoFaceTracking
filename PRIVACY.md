# Privacy policy

Pico Face Tracking (the headset app and its PC modules) collects no personal data. The author runs no servers and gets
nothing from the app: there are no accounts, analytics, crash reports or ads.

## What leaves the headset

| Connection | When | What is sent | To |
| --- | --- | --- | --- |
| Tracking | While tracking is on and the headset is worn | Eye and face tracking values, and the headset's model (for example "PICO 4 Enterprise") | Your PC on the local network (UDP port 9030). Encrypted (AES-256-GCM) once paired by code; unencrypted when paired by address |
| Discovery and pairing | While tracking is on, or while pairing by code | Replies to the PC module's discovery request; while pairing, the headset's device name (or its model) and the pairing exchange (SPAKE2: the code itself never leaves the headset) | Your local network (multicast 239.255.255.250, UDP port 9030) |
| Update check | Only when you press **Check for updates** | An HTTPS request for the latest release, with the user agent `PicoFaceTracking` | GitHub (`api.github.com`) |
| Latest release page | Only when you press **Open the latest release on GitHub** | The browser opens the page | GitHub (`github.com`) |

Tracking data never goes to the internet: only to the PC that requested it on your local network.

GitHub, like any website, sees the headset's IP address and the time of the request when you check for updates or open
the release page. The request carries no other information about you or the headset. GitHub's handling of that is
covered by the [GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).
The app never checks for updates by itself.

## What stays on the headset

The app's settings (paired PC address, language, switches), the pairing key and an imported supporter licence are kept
in the app's private storage. They are removed when you uninstall the app. The licence is checked offline against a
public key in the app.

## On the PC

The modules receive the tracking from the headset on the local network and pass it to OSCFT for VRC or VRCFaceTracking
on the same PC. They store only the pairing key (`PicoFacialData/pairing-key.txt` in your user's config folder,
`%APPDATA%` on Windows). What OSCFT for VRC or VRCFaceTracking does with the tracking (for example sending it to
VRChat over OSC) is covered by those programs.

## PICO's own services

The app reads tracking through PICO's tracking service on the headset. PICO OS and PICO's services have their own
privacy policy, which this app does not change.

## Changes

Changes to this policy are made in this file, so its history on GitHub shows every version.
