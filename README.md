# SpoofMyDevice

SpoofMyDevice 2.0 is an Xposed module and companion app for building, saving, and applying spoofed Android device profiles to selected apps.

The companion UI is written entirely in Jetpack Compose and uses Material 3 Expressive components, a Tonal Spot palette, and the 2025 Material color specification.

## Download

Grab the release-signed APK from the [latest release](https://github.com/kilvz/SpoofMyDevice-eSIM/releases/latest). Builds published before `v2.0.1-esim.2` were debug-signed, so uninstall those before switching.

## Features

- Reusable preset-based and fully editable custom profiles
- Independent app-to-profile assignments
- Dedicated profile management and per-app profile assignment screens
- Live profile propagation to already hooked scoped apps
- Per-field enable controls and one-tap randomization for advanced identifiers
- Safe Mode that keeps the real `Build.VERSION` and `ro.*.build.version.*` values for selected apps
- Model, fingerprint, telephony, and identifier hooks remain enabled in Safe Mode
- eSIM/eUICC reporting with automatic EID generation and NekokoLPA forwarding
- Physical eUICC passthrough for LPA apps (NekokoLPA2, OpenEUICC, EasyEUICC)
- Android System Framework is always excluded from hooking
- Optional resolution, density, window metrics, and configuration spoofing
- Locale, timezone, Build ID, and Display ID spoofing
- English, Korean, Japanese, and Simplified Chinese UI
- Runtime diagnostics and app information screens
- Rooted LSPosed and non-root patch-manager workflows

## Requirements

- Android 8.0 or newer
- One of the following module runtimes:
  - LSPosed on a rooted device
  - A non-root patch manager that implements the legacy Xposed API, such as LSPatch or NPatch

## Rooted LSPosed Setup

1. Install the SpoofMyDevice APK and open it once.
2. Enable SpoofMyDevice in LSPosed.
3. Add only the target apps to the module scope. Do not select Android System Framework.
4. Open **Profiles**, create a profile, then select a preset or edit its custom values and save it.
5. Open **Apps**, select a target app, then choose one of the saved profiles in its assignment screen.
6. Force stop and relaunch newly assigned target apps.

## Non-root Setup

1. Install the SpoofMyDevice APK normally and open it once.
2. In LSPatch or NPatch, register SpoofMyDevice as the Xposed module for the target APK.
3. Patch the target APK and install the patched result.
4. Create and save a profile in **Profiles**, then open **Apps**, select the patched app, and assign that profile.
5. Relaunch the patched target app.

Patched APK signatures differ from the original signatures. Apps that enforce signature, installer, or integrity checks may reject a patched build, and an official app update replaces the patched APK.

## Safe Mode

Safe Mode exists to prevent crashes when the spoofed profile reports a newer Android version than the physical device. For selected apps it bypasses only:

- `Build.VERSION` fields
- `ro.*.build.version.*` properties

Model, fingerprint, network, telephony, and identifier spoofing still applies. Android System Framework is never hooked, regardless of Safe Mode selection.

## Configuration

The companion app stores reusable profiles, app assignments, and global runtime options together in one app-private `app_profiles.json` file. The exported config provider returns only the profile assigned to the requesting package. Packages without an assignment are not hooked.

Applying a profile broadcasts a config-change event so already hooked processes can reload it. A newly assigned app must be relaunched because Xposed hooks are installed when its process starts. Some apps also cache identifiers internally and require a force stop after profile changes.

## eSIM Support

Phone profiles advertise eSIM/eUICC support by default. The module hooks the eUICC-facing telephony surface so target apps see an enabled `EuiccManager`, a stable synthetic EID, and a synthetic `UiccCardInfo` / `UiccPortInfo` marked as an embedded SIM.

When a target app asks Android to install a profile through `EuiccManager.downloadSubscription(...)`, the activation code is extracted and forwarded to NekokoLPA as an `lpa:1$...` deep link (unless the profile disables forwarding). NekokoLPA already handles that URI, so no changes to NekokoLPA are required. This makes carrier or reseller apps that push an eSIM directly to the device hand the request to NekokoLPA instead of failing on hardware that has no real eUICC.

Relevant configuration keys:

| Key | Default | Meaning |
| --- | --- | --- |
| `device.esim` | `true` for phone profiles | Advertise eSIM support |
| `device.eid` | generated | Optional explicit EID; a Luhn-valid value is generated when empty |
| `device.esim_forward` | `true` | Forward `EuiccManager` provisioning requests to NekokoLPA |

The profile editor exposes these as the **eSIM support** and **Forward eSIM installs to NekokoLPA** switches plus an **EID** field in the advanced section. Forwarding uses the NekokoLPA deep-link handler; both package names (`ee.nekoko.nlpa2.open` and `ee.nekoko.nlpa2`) are supported.

### Physical eUICC cards

The synthetic eSIM surface is a fallback: when the framework already reports a real card, subscription, EID or `EuiccManager` state, the module keeps the real value. This is required for removable eUICC cards, which the LPA must be able to enumerate and write to.

SpoofMyDevice also skips telephony and eUICC spoofing for known LPA packages (`ee.nekoko.nlpa2`, `ee.nekoko.nlpa2.open`, `ee.nekoko.nbridge`, `im.angry.openeuicc`, `im.angry.easyeuicc`) so it cannot hide the card from the app that is supposed to manage it. Assigning a profile to NekokoLPA2 is therefore safe, but it will not give the app card access on its own.

If the ROM has no OMAPI service (`android.se.omapi`), an unprivileged OMAPI LPA cannot see the card. NekokoLPA2 can use its privileged **Telephony (TMAPI)** reader instead:

1. Install the NekokoLPA2 privileged Magisk/KernelSU module (`ee.nekoko.nlpa2-<version>.magisk.zip`) and reboot. It installs the app as a privileged app with `MODIFY_PHONE_STATE`; installing only the APK does not grant that permission.
2. Open NekokoLPA2, go to reader settings and enable the **Telephony API** connector.
3. Select the `TM Slot ...` reader for the slot that holds the card. Writes then go through `TelephonyManager.iccOpenLogicalChannel*` instead of OMAPI.

The lighter OTBridge helper module (`otbridge-magisk-kernelsu-*.zip`) provides the same Telephony bridge to the normal app when it is available for your NekokoLPA2 release. EasyEUICC (`im.angry.easyeuicc`) is the unprivileged OMAPI build and has no Telephony fallback, so use NekokoLPA2 or the privileged OpenEUICC build on ROMs without OMAPI.

## Build From Source

```bash
git clone https://github.com/kilvz/SpoofMyDevice-eSIM.git
cd SpoofMyDevice-eSIM
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Signed release builds

The release build reads the signing key from environment variables so the keystore never has to live in the repository:

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

The signed APK is written to `app/build/outputs/apk/release/app-release.apk`.

GitHub Actions signs every build using the same four values stored as repository secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`) and verifies the result with `apksigner` before uploading the artifact.


## Disclaimer

This project is intended for research, testing, and authorized use. Follow applicable laws, platform rules, and the policies of the apps being tested.
