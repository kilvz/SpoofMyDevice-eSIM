# SpoofMyDevice eSIM changelog

Release-signed APKs are built, verified and published by GitHub Actions.
Tags use `v<versionName>-esim.<versionCode>`.

## 2.2.0 (versionCode 21)

Latest release.

### Wider eSIM detection coverage

Apps use several independent signals to decide whether a phone has an eSIM.
This release covers the remaining ones:

- `PackageManager.hasSystemFeature("android.hardware.telephony.euicc")` and the
  MEP flag `android.hardware.telephony.euicc.mep` now report `true` while eSIM
  spoofing is on, independent of the rest of the telephony profile.
- `PackageManager.getSystemAvailableFeatures()` now contains both eUICC
  features even when the ROM does not declare them, so apps that enumerate the
  feature list (instead of calling `hasSystemFeature`) see them too.
- `TelephonyManager.getUiccCardsInfo()` now returns a synthetic embedded eUICC
  card in addition to any real cards when no eUICC is reported, instead of only
  when the list is completely empty. Real cards are never replaced; the
  synthetic card gets an unused card id and physical slot.
- `SubscriptionInfo.isEmbedded()` now reports `true` for subscriptions on an
  eUICC slot, including profiles on a removable eUICC that Android reports as
  non-embedded. Physical SIM subscriptions stay non-embedded.
- `EuiccManager.getAvailableMemoryInBytes()` returns a plausible free-storage
  value when the platform reports none.
- `EuiccManager.isSimPortAvailable()` reports the first two ports as available
  while spoofing, so apps that check port availability before provisioning are
  not blocked.
- `Settings.Global.EUICC_PROVISIONED` reads as `1`, which is what enterprise
  and MDM stacks check for an eSIM-capable device.

Note: these hooks apply to apps in the module's LSPosed scope. Signals computed
server-side (Play Store device catalog, carrier entitlement APIs, Play
Integrity) cannot be changed locally.

## 2.1.7 (versionCode 20)

### Highlights

- A removable eUICC can be read and written by NekokoLPA2 on ROMs that have
  neither the OMAPI secure-element service nor a privileged TMAPI grant, with
  no changes to NekokoLPA2.
- Verified on LineageOS 22 (Android 15, SDK 35) on a Redmi Note 9 Pro
  (`joyeuse`) with an ESTKme removable eUICC in SIM2: the `T-SIM2` reader is
  listed, ISD-R opens and profiles are listed and switched.

### Physical eUICC / eSIM

- Real framework card, subscription, EID and `EuiccManager` state are
  preserved when a physical eUICC is present.
- Telephony and eUICC spoofing is skipped for LPA packages
  (`ee.nekoko.nlpa2`, `ee.nekoko.nlpa2.open`, `ee.nekoko.nbridge`,
  `im.angry.openeuicc`, `im.angry.easyeuicc`) so the module cannot hide the
  card from the app that manages it.
- `EuiccManager.downloadSubscription(...)` provisioning requests are forwarded
  to NekokoLPA as an `lpa:1$...` deep link when spoofing is enabled.

### NekokoLPA2 root bridge

- SpoofMyDevice serves `content://ee.nekoko.nbridge.provider` with the
  `listSlots`, `connectLogicalChannel`, `transmitLogical`, `transmitBasic` and
  `closeLogicalChannel` calls NekokoLPA2's NBridge helper uses.
- The bridge runs through a small `app_process` daemon started on demand with
  `su`; it listens only on `127.0.0.1`, requires a random 192-bit token stored
  in the app's private files, and is restarted automatically after the app or
  the phone restarts.
- Callers are restricted to NekokoLPA2 (both package names), the NBridge
  package, the shell uid, root and the module itself.
- The daemon binds `ITelephony` directly from `ServiceManager` instead of
  `TelephonyManager`, whose lazy telephony helper is null inside an
  `app_process` daemon and made every channel open fail with
  `getTelephonyServiceRegisterer()`.
- Android 15's `IccLogicalChannelRequest` AIDL is supported, including its
  `slotIndex`/`portIndex`/`channel`/`binder` fields.
- `UiccCardInfo`/`UiccSlotInfo` are read through the phone binder, so card
  presence, `isEuicc`, ports and slot state are real.
- Only slots the framework reports as eUICCs are listed once an eUICC is
  detected; normal SIM slots and empty modem slots are not exposed as readers.
  Multi-port/MEP cards appear as `T-SIM1p0`, `T-SIM1p1`, ...
- An optional LSPosed hook inside NekokoLPA2 bypasses a competing OTBridge
  provider, but only intercepts when the root bridge actually answers `ok`.
- Diagnostics: `diag`, `probe`, `experiment` provider methods and
  `/data/local/tmp/spoofmydevice-cardd.log`.

### Version history

- **2.0.x** — eSIM spoofing foundation, EID handling, NekokoLPA forwarding,
  release signing and CI publishing.
- **2.1.0 / esim.13** — built-in NekokoLPA2 root bridge: exported NBridge
  provider plus the root APDU daemon.
- **2.1.1 / esim.14** — root `content call` diagnostics and clearer daemon
  startup errors.
- **2.1.2 / esim.15** — bind `ITelephony` from `ServiceManager`; fixes
  `CHANNEL_OPEN_FAILED` with the `TelephonyServiceManager` NPE.
- **2.1.3 / esim.16** — Android 15 `IccLogicalChannelRequest`, UiccCard/UiccSlot
  metadata via the binder, removed the stray terminal-profile write, daemon
  protocol handshake to replace stale daemons.
- **2.1.4 / esim.17** — AID spelling fallback, `EuiccService` diagnostics and
  the raw `experiment` operation.
- **2.1.5 / esim.18** — daemon protocol bump so 2.1.4's binary replaces the
  running daemon.
- **2.1.6 / esim.19** — hide normal SIM slots once an eUICC is known, never
  retry the gated ISD-R spelling without a privileged LPA, stop retrying after
  `NO_SUCH_ELEMENT`.
- **2.1.7 / esim.20** — do not list empty modem slots as readers.

### Requirements

- Root (Magisk, KernelSU or APatch) granted to **SpoofMyDevice**. NekokoLPA2
  itself does not need root for this path.
- LSPosed is optional: it is only needed when another app (for example
  OTBridge) owns the NBridge provider authority and NekokoLPA2 must be hooked
  to reach the root bridge.

### Install

1. Install the APK over the previous build and open SpoofMyDevice once.
2. Open NekokoLPA2, refresh the reader list, grant root to **SpoofMyDevice**
   when prompted and select the `T-SIM<n>` reader.
3. After updating from 2.1.5 or older, run one provider call (or reboot) so the
   old root daemon is replaced:

   ```
   su -c 'content call --uri content://ee.nekoko.nbridge.provider --method diag'
   ```
