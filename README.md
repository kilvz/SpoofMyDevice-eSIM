# SpoofMyDevice

SpoofMyDevice is an Xposed module and Material 3 companion app for creating
reusable device profiles and applying them to selected apps. It covers the
build, telephony, identifier and display surface that apps use to fingerprint a
device, and it can make a phone look eSIM-capable — including handing a
removable eUICC to NekokoLPA2 on ROMs where nothing else can reach it.

The UI is written in Jetpack Compose with Material 3 Expressive components.

[Latest release](https://github.com/kilvz/SpoofMyDevice-eSIM/releases/latest) ·
[Changelog](CHANGELOG.md)

## Credits

SpoofMyDevice was created by **BuSung-dev**:
[github.com/BuSung-dev/SpoofMyDevice](https://github.com/BuSung-dev/SpoofMyDevice).

This repository (`kilvz/SpoofMyDevice-eSIM`) is an independent fork that adds
the eSIM/eUICC surface and the NekokoLPA2 card bridge. It is **not affiliated
with or endorsed by** the original author. The original project and this fork
are distributed under the MIT License together with the upstream usage terms
(see [LICENSE](LICENSE)).

Thanks also to the LSPosed and Xposed communities, and to the NekokoLPA2,
OpenEUICC and EasyEUICC projects for the LPA side.

## What it does

- Builds reusable presets or fully custom profiles (model, fingerprint, Build
  fields, telephony, identifiers, display, locale, system properties).
- Assigns profiles per app; each app only receives its own assignment.
- Propagates saved changes to already running scoped apps.
- Keeps native values when they are real — a physical eUICC, its EID and its
  subscriptions are never replaced by synthetic data.
- Excludes the Android System Framework from hooking, always.

## Features

**Profiles**

- Preset-based and fully editable custom profiles
- Independent app-to-profile assignments with dedicated management screens
- Live profile propagation to already hooked scoped apps
- Per-field enable controls and one-tap randomization for advanced identifiers
- Model, fingerprint, Build ID, Display ID, resolution, density, window
  metrics, locale, timezone and system-property spoofing

**eSIM / eUICC**

- eSIM reporting with automatic, Luhn-valid EID generation
- Full eSIM detection coverage: eUICC feature flags (including MEP),
  `EuiccManager` state, `UiccCardInfo`, `SubscriptionInfo.isEmbedded` and
  `EUICC_PROVISIONED`
- Activation codes are forwarded to NekokoLPA as `lpa:1$...` deep links
- Physical eUICC passthrough for LPA apps (NekokoLPA2, OpenEUICC, EasyEUICC)
- Built-in NBridge provider and root APDU daemon for NekokoLPA2, for ROMs
  without OMAPI or a privileged TMAPI grant

**Safety**

- Safe Mode that keeps the real `Build.VERSION` and `ro.*.build.version.*`
  values for apps that would crash on a "newer" Android version
- No hooking of the Android System Framework
- Runtime diagnostics and app information screens

**UI**

- English, Korean, Japanese and Simplified Chinese
- Works with rooted LSPosed and with non-root patch managers

## Requirements

- Android 8.0 or newer
- One of:
  - LSPosed on a rooted device
  - A non-root patch manager implementing the legacy Xposed API, such as
    LSPatch or NPatch

## Install

Grab the release-signed APK from the
[latest release](https://github.com/kilvz/SpoofMyDevice-eSIM/releases/latest).
Builds published before `v2.0.1-esim.2` were debug-signed — uninstall those
before switching.

### Rooted LSPosed

1. Install the APK and open it once.
2. Enable SpoofMyDevice in LSPosed.
3. Add only the target apps to the module scope. Never select **Android System
   Framework**.
4. Create a profile in **Profiles**, then assign it to an app in **Apps**.
5. Force stop and relaunch the target app.

### Non-root patch manager

1. Install the APK and open it once.
2. Register SpoofMyDevice as the Xposed module for the target APK in LSPatch or
   NPatch.
3. Patch and install the target APK.
4. Create a profile, assign it to the patched app, and relaunch it.

Patched APKs have a different signature. Apps that enforce signature,
installer or integrity checks may reject them, and an official update replaces
the patched build.

## Safe Mode

Safe Mode prevents crashes when a profile reports a newer Android version than
the device actually runs. For the selected apps it bypasses only:

- `Build.VERSION` fields
- `ro.*.build.version.*` properties

Model, fingerprint, network, telephony and identifier spoofing still apply.

## eSIM support

Phone profiles advertise eSIM support by default. While it is on, the module
covers the signals apps commonly use:

| Signal | Result |
| --- | --- |
| `hasSystemFeature("android.hardware.telephony.euicc")` | `true` |
| `hasSystemFeature("android.hardware.telephony.euicc.mep")` | `true` |
| `getSystemAvailableFeatures()` | contains both eUICC features |
| `EuiccManager.isEnabled()` | `true` |
| `EuiccManager.getEid()` | stable synthetic EID |
| `EuiccManager.getEuiccInfo()` | synthetic eUICC info |
| `EuiccManager.getAvailableMemoryInBytes()` | plausible free space |
| `EuiccManager.isSimPortAvailable()` | first two ports available |
| `TelephonyManager.getUiccCardsInfo()` | real cards plus a synthetic eUICC when none exists |
| `TelephonyManager.getCardIdForDefaultEuicc()` | synthetic card id |
| `SubscriptionInfo.isEmbedded()` | `true` on eUICC slots |
| `Settings.Global.EUICC_PROVISIONED` | `1` |

Real framework data always wins. When the device already reports an eUICC
(including a removable card), its card, EID and subscriptions are passed
through unchanged.

Signals evaluated server-side cannot be changed locally: the Play Store device
catalog (GSF's cached feature list), carrier entitlement/SMDX checks and Play
Integrity still see the real device model.

### Forwarding installs to NekokoLPA

When an app calls `EuiccManager.downloadSubscription(...)`, the activation code
is extracted and sent to NekokoLPA as an `lpa:1$...` deep link. NekokoLPA
already handles that URI, so no changes to NekokoLPA are required. Both package
names are supported: `ee.nekoko.nlpa2.open` and `ee.nekoko.nlpa2`.

### Configuration keys

| Key | Default | Meaning |
| --- | --- | --- |
| `device.esim` | `true` for phone profiles | Advertise eSIM support |
| `device.eid` | generated | Explicit EID; a Luhn-valid value is generated when empty |
| `device.esim_forward` | `true` | Forward provisioning requests to NekokoLPA |

The profile editor exposes these as **eSIM support**, **Forward eSIM installs
to NekokoLPA** and the **EID** field.

## Physical eUICC cards

The synthetic eSIM surface is a fallback. When the framework reports a real
card, subscription, EID or `EuiccManager` state, the module keeps the real
value. This is what lets an LPA enumerate and write a removable eUICC.

Telephony and eUICC spoofing is skipped for LPA packages so the module cannot
hide the card from the app that manages it:

`ee.nekoko.nlpa2`, `ee.nekoko.nlpa2.open`, `ee.nekoko.nbridge`,
`im.angry.openeuicc`, `im.angry.easyeuicc`

### NekokoLPA2: privileged TMAPI reader

If the ROM has working OMAPI, use it. Otherwise NekokoLPA2 can use its
privileged Telephony reader:

1. Install the NekokoLPA2 privileged Magisk/KernelSU module and reboot. It
   installs the app as a privileged app with `MODIFY_PHONE_STATE`; installing
   only the APK does not grant that permission.
2. In NekokoLPA2, enable the **Telephony API** connector.
3. Select the `TM Slot ...` reader for the slot that holds the card.

`otbridge-magisk-kernelsu-*.zip` provides the same bridge to the normal build
when available for your NekokoLPA2 release. EasyEUICC is OMAPI-only and has no
Telephony fallback.

### NekokoLPA2: built-in root bridge

SpoofMyDevice ships its own NBridge provider for both NekokoLPA2 builds. It
answers the same `content://ee.nekoko.nbridge.provider` protocol that
NekokoLPA2 uses with OTBridge, but from a small daemon running as root. It
works without `android.se.omapi`, without a secure-element HAL, and without
`MODIFY_PHONE_STATE` being granted to NekokoLPA2.

How it works:

1. NekokoLPA2 lists readers through its NBridge provider.
2. SpoofMyDevice starts `CardDaemon` through `su` the first time it is asked.
3. The uid 0 daemon opens logical channels through `ITelephony` and forwards
   APDUs to the card.
4. `CardBridgeProvider` answers `listSlots`, `connectLogicalChannel`,
   `transmitLogical`, `transmitBasic` and `closeLogicalChannel`.

Setup:

1. Install this APK and open it once.
2. Open NekokoLPA2 and refresh the reader list. Grant root to
   **SpoofMyDevice** when prompted and keep it granted. NekokoLPA2 itself does
   not need root for this path.
3. Select the `T-SIM<n>` reader. Only slots the framework reports as eUICCs are
   listed once one is detected; empty slots and normal SIM slots are hidden.
   Multi-port cards appear as `T-SIM1p0`, `T-SIM1p1`, …
4. If the reader is missing, make sure no other NBridge helper owns the
   authority. Uninstall or disable OTBridge, or add NekokoLPA2 to the
   SpoofMyDevice LSPosed scope so the module can intercept NBridge calls.

After updating from 2.1.5 or older, one provider call (or a reboot) replaces a
daemon left running from the old APK:

```bash
su -c 'content call --uri content://ee.nekoko.nbridge.provider --method diag'
```

The first call also starts the daemon; `diag` reports the bind result, the
detected slots and the eUICC features the ROM exposes.

## Diagnostics

```bash
# From a rooted terminal, or prefix each line with `adb shell`:
su -c 'content call --uri content://ee.nekoko.nbridge.provider --method diag'
su -c 'content call --uri content://ee.nekoko.nbridge.provider --method probe'
su -c 'cat /data/local/tmp/spoofmydevice-cardd.log'
```

- `diag` — daemon version, slots, card state, `ITelephony` bind state, ICC
  methods and `EuiccService` components (`euiccService.bestPackage`).
- `probe` — opens the ISD-R applet (`A0000005591010FFFFFFFF8900000100`) on the
  first slot and reports the select result. `probe --extra slot:i:1` targets
  SIM2. A `channel` with `status=1` means the bridge can read and write the
  card.
- `/data/local/tmp/spoofmydevice-cardd.log` — channel opens, APDU errors and
  daemon startup failures.

Android 15 only lets the default privileged LPA open the ISD-R applet, which a
root bridge is not, so the daemon spells the AID in lower case. AIDs are
binary, so the card is unaffected while the framework's case-sensitive guard is
skipped.

The provider accepts calls only from `ee.nekoko.nlpa2`,
`ee.nekoko.nlpa2.open`, `ee.nekoko.nbridge`, the shell user, root and
SpoofMyDevice itself. The daemon listens on `127.0.0.1` only and requires a
random 192-bit token stored in the app's private files.

## Configuration storage

Profiles, app assignments and global options live in one app-private
`app_profiles.json` file. The exported config provider returns only the profile
assigned to the requesting package; packages without an assignment are not
hooked.

Applying a profile broadcasts a config-change event so already hooked processes
reload it. A newly assigned app must be relaunched because Xposed hooks are
installed when its process starts. Some apps cache identifiers internally and
need a force stop after a profile change.

## Build from source

```bash
git clone https://github.com/kilvz/SpoofMyDevice-eSIM.git
cd SpoofMyDevice-eSIM
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Signed release builds

The release build reads the signing key from environment variables:

| Variable | Meaning |
| --- | --- |
| `SIGNING_KEYSTORE_PATH` | Path to the PKCS#12/JKS keystore |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

```bash
export SIGNING_KEYSTORE_PATH=/path/to/release.jks
export SIGNING_STORE_PASSWORD=...
export SIGNING_KEY_ALIAS=spoofmydevice
export SIGNING_KEY_PASSWORD=...
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

GitHub Actions signs every release with the repository secrets
(`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`,
`SIGNING_KEY_PASSWORD`), verifies the APK with `apksigner`, and publishes it.

## Releases

- Tags use `v<versionName>-esim.<versionCode>` (for example
  `v2.2.0-esim.21`).
- Only the latest release is kept; older ones are removed.
- Release notes and the full history are in [CHANGELOG.md](CHANGELOG.md).
- Signing certificate SHA-256:
  `ea51259a82e0de08330b2c7bd1419f71412b5a20b95dca3134b9ced71e20578b`

## Verified configuration

- LineageOS 22 (Android 15, SDK 35) on a Redmi Note 9 Pro (`joyeuse`)
- ESTKme removable eUICC in SIM2
- NekokoLPA 2 Community, no OMAPI, no privileged TMAPI grant

With this setup the root bridge lists `T-SIM2`, opens ISD-R and lets
NekokoLPA2 list and switch profiles.

## License and upstream policy

SpoofMyDevice is distributed under the MIT License. The full license text and
the upstream disclaimer are in [LICENSE](LICENSE); the same terms apply to this
fork.

- Provided for educational, testing and development purposes only.
- Use it only on devices you own or are authorized to modify.
- Do not use it to bypass app restrictions, violate terms of service, or engage
  in fraudulent activity.
- The authors are not responsible for misuse, damage or terms-of-service
  violations, and provide no warranty.

By using this software you accept these terms and full responsibility for the
consequences.
