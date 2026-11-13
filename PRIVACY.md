# Privacy policy

Pico Face Tracking (the headset app and its PC modules) collects no personal data. The author runs no servers and gets
nothing from the app: there are no accounts, analytics, crash reports or ads.

The app shows this file under Settings, **Privacy and data**.

## Tracking

While tracking is on and the headset is worn, the app sends eye and face tracking values and the headset's model (for
example PICO 4 Enterprise) to your PC on the local network (UDP port 9030). They are encrypted (AES-256-GCM) once paired
by code and unencrypted when paired by address. Tracking data never goes to the internet.

## Discovery and pairing

- The app answers the PC module's discovery request on the local network (multicast 239.255.255.250, UDP port 9030).
- While pairing by code, it sends the headset's device name (or its model) and the pairing exchange (SPAKE2). The code
  itself never leaves the headset.

## Updates and GitHub

- **Check for updates**: only when you press it, the app asks GitHub (`api.github.com`) for the latest release over
  HTTPS, with the user agent `PicoFaceTracking`. The app never checks by itself.
- **Open the latest release on GitHub** and **Full policy on GitHub** open `github.com` in the browser.

GitHub, like any website, sees the headset's IP address and the time of the request. The request carries no other
information about you or the headset. GitHub's handling of that is covered by the
[GitHub General Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

## On the headset

The app's settings (paired PC address, language, switches), the pairing key and an imported supporter licence stay in
the app's private storage. They are removed when you uninstall the app. The licence is checked offline against a public
key in the app.

## On the PC

The modules receive the tracking from the headset on the local network and pass it to OSCFT for VRC or VRCFaceTracking
on the same PC. They store only the pairing key (`PicoFacialData/pairing-key.txt` in your user's config folder,
`%APPDATA%` on Windows). What OSCFT for VRC or VRCFaceTracking does with the tracking (for example sending it to VRChat
over OSC) is covered by those programs.

## PICO's own services

The app reads tracking through PICO's tracking service on the headset. PICO OS and PICO's services have their own
privacy policy, which this app does not change.

## Changes

Changes to this policy are made in this file and its translations (`PRIVACY.ja.md`, `PRIVACY.zh.md`), so their history
on GitHub shows every version. If a translation differs, this English file applies.
