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

Merge to `main`, bump the version (see below), commit as `Version X.Y.Z`, then tag:

```
git tag v1.12.1
git push origin v1.12.1
```

The tag build adds the paid features from the private repository and publishes the APK, `Pico4FaceTracking-VRCFT.zip`,
`Pico4FaceTracking-OSCFT-native.zip` and their checksums as a GitHub release.

### One version for app and modules

The app, the .NET module and the native module share one version number. OSCFT for VRC and VRCFaceTracking read
`Version` from the module's `module.json` and compare it with the latest release to decide whether an update is
available. If the module version lags behind the release tag, users are told about an update forever, or never.

Every release, set the same version in:

| File | Field |
|---|---|
| `app/build.gradle` | `versionName` (and bump `versionCode` by 1) |
| `module/module.json` | `Version` |
| `native/module.json` | `Version` (CI overwrites it from `module/module.json`, keep it in sync anyway) |

The tag is that version with a `v` prefix: `versionName '1.12.2'` → tag `v1.12.2` (`v1.12.2-rc1` publishes a
pre-release). Bump all three even if only the app or only a module changed.

Check before tagging:

```
grep -n versionName app/build.gradle
grep -n '"Version"' module/module.json native/module.json
```

### Release notes

The `release` job in `.github/workflows/build.yml` writes the notes. Keep them short. Installation details belong in
the README, and the checksums are already attached as `.sha256` files:

```markdown
Install `PicoFaceTracking.apk` on your headset, then install the tracking module in OSCFT.

**Which module (1.12.1)**
- [OSCFT for VRC](https://github.com/codeyumx/oscft_for_vrc) 0.2.0 or later: `Pico4FaceTracking-OSCFT-native.zip`
  (native, Windows and Linux, no .NET). Install it on the Modules page.
- [VRCFaceTracking](https://github.com/benaclejames/VRCFaceTracking): `Pico4FaceTracking-VRCFT.zip`. Module Registry,
  +, pick the zip.
```
