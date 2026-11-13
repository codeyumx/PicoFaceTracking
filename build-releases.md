# Building the app without a local Android toolchain

If the machine you develop on has no JDK, Android SDK or signing keystore, let GitHub Actions build the APK
(`.github/workflows/build.yml`) and install it over adb.

## Why CI and not a local debug build

The app on the headset is release-signed. `adb install -r` only replaces it with an APK signed by the same key.
A local debug build is signed with a different key, so Android refuses it unless you uninstall the app first, and that
deletes its settings, the paired PC and the pairing key. CI signs with the release key from the repository secrets
`SIGNING_KEYSTORE_BASE64` and `SIGNING_PASSWORD`. Builds from forks and pull requests from forks have no secrets and
come out debug-signed.

## Which build you get

| Trigger | Signing | Paid adjustments (`pro/src`) | Published |
|---|---|---|---|
| Push to `main`, `workflow_dispatch` on any branch, pull request in this repository | release key | no | artifact `PicoFaceTracking` |
| Tag `v*` (for example `v1.12.1`) | release key | yes, needs `PRO_DEPLOY_KEY` | GitHub release (`v1.12.1-rc1`: pre-release) |
| Fork or pull request from a fork | debug key | no | artifact `PicoFaceTracking` |

Every build runs the unit tests first (`gradle testDebugUnitTest`).

## Test a branch on the headset

Requires the [GitHub CLI](https://cli.github.com/) (`gh auth login`) and adb with the headset connected.

```
git switch -c my-change
git commit -am "..."           # this checkout may have no git identity:
                               # git -c user.name=codeyumx -c user.email=codeyumx@users.noreply.github.com commit ...
git push -u origin my-change
gh workflow run build.yml --ref my-change
gh run list --branch my-change --limit 1          # note the run id
gh run watch <run id> --exit-status
gh run download <run id> -n PicoFaceTracking -D build-out
adb install -r build-out/PicoFaceTracking.apk
```

`PicoFaceTracking.apk.sha256` next to the APK holds its checksum. A branch build has no paid adjustments; the settings
screen says so. Installing a tag build later brings them back.

If the build fails, `gh run view <run id> --log-failed` shows the failing step.

## Release

Merge to `main`, bump `versionName` / `versionCode` in `app/build.gradle`, then tag:

```
git tag v1.12.1
git push origin v1.12.1
```

The tag build adds the paid features from the private repository and publishes the APK, `Pico4FaceTracking-VRCFT.zip`,
`Pico4FaceTracking-OSCFT-native.zip` and their checksums as a GitHub release.

### Release notes

The `release` job in `.github/workflows/build.yml` writes the notes. Keep them short. Installation details belong in
the README, and the checksums are already attached as `.sha256` files:

```markdown
Install `PicoFaceTracking.apk` on your headset, then install the tracking module in OSCFT.

**Which module (1.12.0)**
- [OSCFT for VRC](https://github.com/codeyumx/oscft_for_vrc) 0.2.0 or later: `Pico4FaceTracking-OSCFT-native.zip`
  (native, Windows and Linux, no .NET). Install it on the Modules page.
- [VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking): `Pico4FaceTracking-VRCFT.zip`. Module Registry,
  +, pick the zip.
```
