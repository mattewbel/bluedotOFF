# Indicator Eligibility Research — Phase 7.2

Research completed before Picker implementation: 2026-10-05, Fold7 boot 55. Target permission metadata writes: **0**. Hide/Restore/reapply and permission grant/revoke were not used. Device getters, a fixed temporary shell getter probe, natural SystemUI logs, PC decompilation and public-source research were used. Local private evidence: `research/phase72_20261005` (not packaged).

## Conclusion and proposed display policy

The current Fold7 distinguishes logical **system usage** using the permission sensitivity of the **FINE AppOp**, not `ApplicationInfo.FLAG_SYSTEM`. For a current-profile FINE event, preflight result 0 chooses `flags & 0x100`; a nonzero result chooses `flags & 0x200`. A zero chosen bit means `isSystemApp=true`. This is independent of installation origin, launcher presence and bluedotOFF write safety.

Default Picker should show strong future FINE indicator candidates with the existing ownership precheck. It should preserve registered targets even when their original sensitivity bit has been cleared. Unknown and excluded applications remain in an explicitly secondary **고급: 전체 위치 앱** view. A candidate is not a claim that a dot was physically displayed, nor authorization to write. Selected targets still pass the unchanged fresh SafetyEngine checks.

History continues to describe past positive access; it is not a list of future Picker candidates. No new package blacklist is justified. Continuity/GMS/customization services are excluded by their actual nonsensitive FINE state. Google Maps and Samsung user-facing apps are retained when their actual state qualifies.

## Public source ledger

All sources accessed 2026-10-05. AOSP Android 17 tag **android-17.0.0_r1**, tag object `23149ba144e850fe494a91ffa13f8019455c0804` (2026-06-16), points to commit **94b4c163b7dfe5ce3607f7bb8456f9573f7de57d** (2026-05-21). This pinned release was downloaded and inspected; older `main` results were not substituted for Android 17. Source-file hashes and URLs are in private `aosp/sources.json`.

| Source | Date / authority | Finding and limitation |
|---|---|---|
| [Android Developers: redefining location privacy](https://developer.android.com/blog/posts/redefining-location-privacy-new-tools-and-improvements-for-android-17?hl=en) | Android 17, published 2026 | Describes a persistent location indicator for nonsystem apps and recent-use attribution. Does not define nonsystem as APK installation flags. |
| [AOSP privacy indicators](https://source.android.com/docs/core/permissions/privacy-indicators) | official, accessed 2026-10-05 | Describes Android 17 foreground nonsystem location indications, 10-second minimum active display and 20-second recent location use. AppOps callbacks feed SystemUI. |
| [Android 17 release tag](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-17.0.0_r1) | pinned release above | Exact source provenance. |
| [AppOpsPrivacyItemMonitor](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/SystemUI/src/com/android/systemui/privacy/AppOpsPrivacyItemMonitor.kt) | Android 17 r1 | FINE-only OPS_LOCATION; current profiles; preflight-selected sensitivity; AOSP additionally requires nonbackground importance. |
| [AppOpsControllerImpl](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/SystemUI/src/com/android/systemui/appops/AppOpsControllerImpl.java) | Android 17 r1 | Active and noted queues, indicator package visibility and successful noted-event filtering. |
| [LocationControllerImpl](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/SystemUI/src/com/android/systemui/statusbar/policy/LocationControllerImpl.java) | Android 17 r1 | Legacy location status-icon handling, including COARSE/high-power and show-system controls. Not interchangeable with the blue PrivacyItem path. |
| [PermissionManager](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/permission/PermissionManager.java) | Android 17 r1 | Indicator exemption is `android` plus six configured intelligence roles, cached for 15 seconds. It is only one upstream gate. |
| [PackageManager constants](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/content/pm/PackageManager.java) | Android 17 r1 | USER_SENSITIVE_WHEN_GRANTED is 1<<8; WHEN_DENIED is 1<<9. |
| [PermissionManagerService](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/pm/permission/PermissionManagerService.java) | Android 17 r1 | Preflight uses a non-delivery raw AppOps check, not note/start data delivery. Normal granted raw modes 0/4 resolve to preflight-granted. |
| [Samsung privacy dashboard explanation](https://insights.samsung.com/2023/08/17/what-is-privacy-dashboard-and-how-does-it-protect-my-data-2/) | Samsung, 2023-08-17 | Explains historical permission usage, not One UI 9 indicator predicates. |
| [Samsung location permissions](https://www.samsung.com/sg/support/mobile-devices/how-to-activate-my-location-and-change-settings-for-location-permissions/) | Samsung support, accessed 2026-10-05 | User permissions and location settings; no exact One UI 9 nonsystem formula. |
| [Samsung green-dot support](https://www.samsung.com/ph/support/mobile-devices/know-what-the-green-dot-at-the-top-of-your-screen-means/) | Samsung, 2022-05-25 | Camera/microphone only. Not evidence of the blue location filter. |
| [Samsung Community blue indicator](https://eu.community.samsung.com/t5/galaxy-s26-series/blue-indicator-dot/m-p/15453394/highlight/true) | user observation, 2026-10, search excerpt; full fetch unavailable | Reports that some system location usage does not show a dot. No controlled classification evidence. |
| [One UI blue-dot discussion](https://www.reddit.com/r/oneui/comments/1wvp4g5/annoying_location_blue_dot/) | users, 2026-10-02 | Reports blue-dot/status-bar behavior and region-dependent settings. Useful consistency check, not a predicate or reliable regional guarantee. |
| [Continuity/phone older observations](https://www.reddit.com/r/GalaxyS25/comments/1kn5vdm/location_icon_constantly_turning_off_and_on/) | users, 2025, One UI 7 | Concerns an older location icon; cannot establish One UI 9 privacy-dot policy. |
| [GitHub AOSP Internals Android 17 appendix](https://aospbooks.github.io/aosp-internal-book/D-appendix-android-17-updates/) | community source analysis, accessed 2026-10-05 | Points to new location privacy-chip source. All implementation conclusions were independently checked against pinned AOSP/current firmware. |
| [XDA older ROM thread](https://xdaforums.com/t/rom-12-1-starlte-star2lte-eol-official-evolution-x-08-12-2022.4438717/) | custom ROM, 2022 | Custom phone/Bluetooth exemptions differ by ROM. Not a basis for Samsung package blacklists. |

Samsung official/developer/security/privacy searches did not locate an authoritative published One UI 9 predicate matching these methods. This is a search limitation, not evidence that no such document exists. Community observations agree only at the broad level that recorded system location usage and visible indicators can differ.

Search coverage: Android 17 location privacy indicator system app; Android 17 nonsystem location indicator; AOSP location indicator system app; AppOpsPrivacyItemMonitor location isSystemApp; LocationControllerImpl isSystemApp; PermissionManager shouldShowPackageForIndicatorCached; USER_SENSITIVE_WHEN_GRANTED indicator; USER_SENSITIVE_WHEN_DENIED indicator; Android getIndicatorExemptedPackages; location indicator exempt packages; Samsung One UI location privacy indicator; Samsung SystemUI location indicator; Samsung location privacy dot system apps; Samsung AppOpsPrivacyItemMonitor; One UI 9 location indicator; system apps not showing location indicator Android 17. Queries with no precise result were retained as negative search evidence, not guessed implementation facts.

## Current firmware and exact path

SM-F966N, One UI 90000 / SDK 37, `F966NKSUCCZIH`; fingerprint `samsung/q7qksx/q7q:17/CP2A.260605.016/F966NKSUCCZIH_OKRCCZIH:user/release-keys`. Country getter KR. Device and existing PC files have identical hashes:

| File | SHA-256 |
|---|---|
| `/system_ext/priv-app/SystemUI/SystemUI.apk` | `6c463b842b511ebb8277cc5d3e332a1679e13fb944e45a51757767a03499301b` |
| `/system/framework/framework.jar` | `4b75a3b3dcea2deebaa403d6021da9e9bf9c9f9336a6956a69594884472086eb` |
| `/system/framework/services.jar` | `29af5a05172b7c95e7f2a9efadc8d343daf5d51852a3644c7e2ce204da2709a8` |

Local source citations below are paths beneath `research/`: `systemui-decompiled/sources/com/android/systemui/…`, `systemui-simple/PrivacyItemController$updateListAndNotifyChanges$1.java`, `phase61_20261004/code/android.permission.PermissionManager.java`, and the newly selected `phase72_20261005/code/com.android.server.adb.*.java`. Decompiler output with broken branches was cross-checked against simplified instruction output and live logs; no broken Java was executed.

Path: successful AppOps active/noted event → `appops/AppOpsControllerImpl.onOpActiveChanged/onOpNoted` → `getActiveAppOps()` and `isUserVisible()` → current-profile selection → `privacy/AppOpsPrivacyItemMonitor.shouldDisplayLocationOp()` → `toPrivacyItemLocked()` → `PrivacyItemController` distinct/paused/hold processing → `PrivacyItemController.Callback.onPrivacyItemsChanged` → chip/status-bar privacy controllers. The blue-dot/status-icon visibility is logged by `PrivacyLogger`; an access record alone does not prove its screen layer was rendered.

## Predicate matrix

| Predicate | Actual impact in this Fold7 path | Source / live evidence |
|---|---|---|
| Op type | **Yes**: OP_FINE_LOCATION=1. COARSE=0 is not in blue monitor OPS_LOCATION. | monitor static block/toPrivacyItemLocked; AOSP17 agrees. Legacy LocationController handles a different icon path. |
| Current user/profile | **Yes**: only tracked profiles; wrong-profile usage treated as system and filtered. | isSystemApp lines 344–364; update-list profile filtering. Product scope remains user 0. |
| Permission/preflight | **Yes**: preflight=0 selects 0x100; otherwise 0x200. | exact temporary framework getter returned 0 for FINE/COARSE of all 15 sampled packages. Core UID permission checks may return 0 despite no manifest declaration. |
| 0x100 / 0x200 | **Yes**, branch-specific. Neither is FLAG_SYSTEM. | current fine flags table; prior OFF/ON experiments and current natural isSystem logs. |
| Indicator package exemption | **Yes**, upstream. | PermissionManager returns android + configured roles. Does not exclude GMS/Continuity by name. |
| Attribution tag | **Yes** upstream exceptions/trusted-chain handling, not a new sensitivity bit. | Samsung isUserVisible allows SLocationService/Biometrics_FaceService tags through package exemptions; active callback attribution flags gate chain visibility. |
| UID/appId | Profile resolution matters; **no application-range test in isSystemApp**. | Application/core UID constraints belong to SafetyEngine, not the sensitivity predicate. |
| Shared UID | **No direct gate** in logical sensitivity classifier. May affect UID-level grants/ops. | SafetyEngine independently blocks multiple peers; singleton policy unchanged. |
| Persistent/core | **No direct gate** in isSystemApp. | Sample internal usage is nonsensitive; do not substitute persistent for sensitivity. Safety independently blocks it. |
| APK FLAG_SYSTEM / UPDATED_SYSTEM_APP | **No direct gate** in this monitor. | Google Maps SYSTEM+UPDATED_SYSTEM_APP is sensitive. |
| Launcher | **No gate**. | Monitor never queries launcher. Product should not require it. |
| Foreground/background importance | AOSP17 **yes** (>125 background excluded). Samsung current **logging only here**, not a return-value gate. | Samsung shouldDisplayLocationOp computes background then returns showSystem-or-nonsystem without `!background`; AOSP returns both nonsystem and nonbackground. Future candidate need not currently be foreground. |
| Running/noted | **Yes**: actual current queue required; successful noted mode only, 10-second location queue retention. | AppOpsControllerImpl lines 183–211, 336+. Future Picker predicts potential, not current queue membership. |
| Feature/showSystem | **Yes**. locationAvailable is hard true here; showSystem override only QUICK_SUPPORT_LOCATION_PRIVACY_CHIP with US CountryISO. | ScRune line 26; KR and null show_status_bar_location_icon. Secure locationShowSystemOps null / device_config show_system=false affect legacy path, not this blue monitor override. |
| Samsung package special case | **Yes** upstream: platform-signed `com.samsung.android.sead` or `com.samsung.sightcare` invisible. | isUserVisible lines 235–239. Current sead appId1000/persistent; sightcare not installed. Both outside safe default targets. Product must not invent a blacklist from this observation. |
| Sensor disabled / item hold | **Yes** later paused/holding state; current dot can outlive a short event. | getActiveAppOps and PrivacyItemController paused/hold code. Not a package-eligibility shortcut. |
| Future OS sensitivity reset | **Uncertain timing**, actual previously observed. | PermissionController may recompute flags. Prediction is refreshed; it never proves APPLIED or triggers a write. |

Live role resources from `cmd overlay lookup --user 0`, independently for android/SystemUI/bluedotOFF: ambient audio empty; UI intelligence **com.google.android.as**; audio intelligence **com.google.android.as**; notification/text empty; visual intelligence **com.samsung.android.smartface**. Effective nonempty exemptions: android, com.google.android.as, com.samsung.android.smartface. No added package blacklist.

A temporary unbound app_process Context misleadingly returned empty framework resources and an empty cached role set. Its role output is **not** used as live exemption truth. Overlay lookup confirms all three bound package contexts have the same configured values. The product's ordinary bound Context resource adapter must be validated on-device; resource/hash failure produces UNKNOWN rather than a guessed candidate. The exact preflight calls themselves matched grants/raw modes and current SystemUI logs.

## Device correlation

| 앱/package | UID/appId · peers | launcher / preinstalled | F/C 선언·grant | F/C flags · FINE 100/200 | raw F/C | 최근 F/C positive access(KST) | logical system / exemption | current PrivacyItem / physical | old Picker → proposed |
|---|---|---|---|---|---|---|---|---|---|
| 카카오맵<br>`net.daum.android.map` | 10502/10502 · 1 | True / False | True/True · 0/0 | `0x300`/`0x300` · True/True | 4/4 | 10-05 09:43/없음 | False / role allowed | 현재 event 미확인<br>이전 ON/OFF/ON 확인 | True → True (민감+ownership) |
| Google Maps<br>`com.google.android.apps.maps` | 10293/10293 · 1 | True / True | True/True · 0/0 | `0x300`/`0x300` · True/True | 4/4 | 09-04 10:59/없음 | False / role allowed | 현재 event 미확인<br>현재 육안 미확인 | True → True (민감+ownership) |
| 미세미세<br>`cheehoon.ha.particulateforecaster` | 10474/10474 · 1 | True / False | True/True · 0/0 | `0x200`/`0x300` · False/True | 0/0 | 10-05 18:11/없음 | True / role allowed | 현재 event 미확인<br>7.1 재부팅 재적용 OFF 확인 | True → False (system usage/core) |
| 날씨날씨<br>`com.lifeoverflow.app.weather` | 10078/10078 · 1 | True / False | True/True · 0/0 | `0x80201`/`0x301` · False/True | 0/0 | 10-05 18:19/없음 | True / role allowed | 현재 event 미확인<br>6.1 OFF 확인 | True → True (등록 유지) |
| 삼성 날씨<br>`com.sec.android.daemonapp` | 10232/10232 · 1 | True / True | True/True · 0/0 | `0x80301`/`0x320` · True/True | 0/0 | 10-05 17:29/없음 | False / role allowed | 현재 event 미확인<br>현재 육안 미확인 | True → True (민감+ownership) |
| 전화(사용자 dialer)<br>`com.samsung.android.dialer` | 10129/10129 · 2 | True / True | True/True · 0/0 | `0x300`/`0x300` · True/True | 4/4 | 10-05 18:47/없음 | False / role allowed | 현재 로그 nonsystem/항목 확인<br>현재 육안 미확인 | True → False (다중 peer) |
| 카메라<br>`com.sec.android.app.camera` | 10126/10126 · 1 | True / True | True/True · 0/0 | `0x300`/`0x380` · True/True | 4/4 | 10-03 21:24/없음 | False / role allowed | 현재 event 미확인<br>현재 육안 미확인 | True → True (민감+ownership) |
| Google Play services<br>`com.google.android.gms` | 10273/10273 · 1 | False / True | True/True · 0/0 | `0x30`/`0x30` · False/False | 0/0 | 10-05 18:51/10-05 18:30 | True / role allowed | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| Android<br>`android` | 1000/1000 · 93 | False / True | False/False · 0/0 | `0x30`/`0x30` · False/False | 0/0 | 10-05 18:48/10-05 17:24 | True / 除外(android) | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| Samsung Location SDK<br>`com.sec.location.nsflp2` | 5013/5013 · 1 | False / True | True/True · 0/0 | `0x30`/`0x30` · False/False | 0/0 | 10-05 18:47/없음 | True / role allowed | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| Continuity<br>`com.samsung.android.mcfds` | 10104/10104 · 1 | False / True | True/True · 0/0 | `0x20`/`0x20` · False/False | 0/0 | 10-05 18:49/07-23 22:54 | True / role allowed | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| 맞춤형 서비스<br>`com.samsung.android.rubin.app` | 10114/10114 · 1 | False / True | True/True · 0/0 | `0x20`/`0x20` · False/False | 0/0 | 10-05 18:47/없음 | True / role allowed | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| 전화 backend<br>`com.android.phone` | 1001/1001 · 16 | False / True | True/True · 0/0 | `0x30`/`0x30` · False/False | 4/4 | 10-05 18:47/없음 | True / role allowed | 자연 로그 system/필터<br>현재 육안 미확인 | True → False (system usage/core) |
| 당근<br>`com.towneers.www` | 10229/10229 · 1 | True / False | True/True · 0/0 | `0x300`/`0x300` · True/True | 4/4 | 10-04 15:21/없음 | False / role allowed | 현재 event 미확인<br>현재 육안 미확인 | True → True (민감+ownership) |
| 토스<br>`viva.republica.toss` | 10366/10366 · 1 | True / False | True/True · 0/0 | `0x300`/`0x300` · True/True | 4/4 | 10-05 09:32/없음 | False / role allowed | 현재 event 미확인<br>현재 육안 미확인 | True → True (민감+ownership) |

Legend: grant `0` = PackageManager granted; raw mode `0` = allow, `4` = foreground. F/C = FINE/COARSE. Times describe positive access; no coordinates were collected. Current Picker = old launcher-or-positive-access filter before the ownership/UI block. Proposed default additionally requires strong indicator candidate + existing lightweight safety precheck, except registered targets remain available. Managed retention is not proof of current visibility.

Physical observations are explicitly historical user confirmations (Kakao ON/OFF/ON, Mise OFF after actual reboot, LifeWeather OFF); no new physical observation is claimed from getters. Current natural SystemUI logs show GMS, Location SDK, customization, Android and Continuity as system usage; dialer as nonsystem with location PrivacyItems. Internal services were not launched, granted or forced. Camera/Maps states predict potential display; no current event means current PrivacyItem/physical dot is unknown, not absent by proof.

## Wireless Debugging exit feasibility

[AOSP adbd startup documentation](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/how_adbd_starts.md), [pinned Android17 AdbService](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/adb/AdbService.java), and [SettingsProvider](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java) establish the control path. Exact key: `Settings.Global.ADB_WIFI_ENABLED` / `adb_wifi_enabled`. Provider global mutation requires WRITE_SECURE_SETTINGS. Current com.android.shell UID2000 has it granted. Live wireless=1, USB ADB=1, developer options=1.

Current Samsung AdbSettingsObserver observes that URI and dispatches Wi-Fi transport enable/disable; AdbService changes `persist.adb.tls_server.enable`; AdbDebuggingManager handles Wi-Fi shutdown and connection notifications. Auto Blocker/MDM/device restrictions add branches. An OS-level shell OFF is feasible from source/authorization evidence, **not yet an actual successful OFF trial**. Developer Options and USB debugging are distinct settings and must never be changed by this feature.

The production helper admits only its fixed getter set and narrow FINE setter. It has no SettingsProvider write operation; LocalAdbBackend has no exit command. Section 40 explicitly freezes both. Therefore test.9 should ship the supported **Settings shortcut fallback**, not silently expand the privileged protocol or present untested automatic OFF as working. No OFF trial is necessary to validate a shortcut. If a later phase authorizes protocol expansion, its separate approved trial must follow all target settling/readback + durable journal/fsync, then a final fixed OFF command, treat socket closure as expected, and retain BlueDot success if OFF fails. Trial without that app path would test PC shell feasibility only and cannot certify product ordering or durability.

## Implementation boundary and remaining uncertainty

Research is sufficient for a separate, conservatively named **strong candidate** model on this verified firmware. Normal declared/granted FINE+COARSE, raw modes 0/4 and live FINE 0x100 support the granted/preflight branch. Core/special installed packages cannot become safe default targets. Differing/unknown raw modes or failed reads are advanced-only. Denied sensitivity is represented independently from the granted state; a nonsystem denied branch does not make a target write-safe. Managed hidden targets remain without replacing their live classification with stored bits.

The model describes potential FINE indicators and deliberately does not claim full instantaneous dot equivalence across attribution chains, changed firmware/regions, concurrent apps, or screen-layer rendering. All default list exclusions have an inspectable reason. SafetyEngine, journal, singleton verification, LocalAdbBackend, helper, setter, settling and reapply remain byte-identical. Guide assets should use reusable One UI-style SVG illustrations with no real codes/IP/device/Wi-Fi/notification data. Current setup step alone is shown; help contains all four.


## Post-implementation validation addendum — 2026-10-05

The preceding report and its table were sealed before product edits. Its original SHA256 is `15894de405b4b574045431a0d034b7c988b83c77b0cea640d0720de3cc14c634`; an exact copy remains in private research evidence. The following observations are later validation, not retroactive pre-implementation claims.

The installed test.9 bound Context resolved the live role resources correctly: effective exemptions android, com.google.android.as and com.samsung.android.smartface. Profile/fingerprint/SystemUI hash checks succeeded. Actual discovery covered778 installed items;76 strong candidates,54 exempt system usages,3 exempt packages,566 ineligible FINE declarations,78 permission-state cases and1 unknown. Existing ownership checks exclude2 strong candidates;1 managed exempt target remains, producing75 default entries. UNKNOWN is a Trichrome static-library alias whose full PackageInfo lookup does not resolve, not a guessed safe UID.

Actual Picker searches confirmed KakaoMap and Google Maps present; Continuity absent by default and present in advanced view. Camera, Samsung Weather, Carrot and Toss are candidates. Dialer is logically nonsystem but has two authoritative peers and remains blocked by unchanged ownership safety. Mise was already FINE0x200 and unmanaged when this phase began; its proposed exclusion means **logical system usage because FINE0x100 is OFF**, not a core UID. LifeWeather was already the one enabled managed target, retained with originalBit256. No older managed set was reconstructed.

A new natural KakaoMap event at19:27KST had FINE op1, isSystem=false and a location PrivacyItem; the user confirmed both physical dot and icon visible. No Hide/Restore followed. Other physical observations in the original table remain historical or unmeasured as labeled.

All15 packages retained grants, full FINE/COARSE flags, raw modes, identity/UID peers and declarations after installation/UI acceptance and the approved OS trial. Metadata setter delta0; existing log baseline1 did not increase. Managed ownership/originalBit/state stayed unchanged. App SafetyEngine, Journal, LocalAdbBackend, helper, setter, settling and reapply are protected against the delivered test.8 baseline.

The separately approved PC USB shell UID2000 trial executed `settings put global adb_wifi_enabled 0` exactly once after readback/checksum and all helper batches closed. Setting1→0; USB/developer options1 and stay-awake7 retained; journal exact digest preserved across OFF. TLS properties were empty even before the trial, so their assertion could not establish shutdown; a getter-only follow-up read the exact AdbService adb_wifi section: enabled=false, tls_port=0. No second OFF or ON write occurred. The trial certifies OS feasibility, not an app automatic-OFF transaction or active-socket response ordering. The product therefore ships Settings fallback only, respecting section40. On this device it opens Developer Options with the wireless row visible. Offline Home preserves registration without a fresh APPLIED claim; current Setup shows only step2 artwork. Help shows all four steps.

For complete delivery status and limits, see `Phase-7.2-report.md`. Device evidence is private and excluded from the source ZIP.
