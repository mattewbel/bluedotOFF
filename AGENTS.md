# AGENTS.md — bluedotOFF

Instructions and handoff notes for coding agents (Codex, Claude Code, …) working in this repository.

## What this is

An Android app (Samsung One UI 9 / Android 17, SDK 37) that hides the blue location indicator for user-selected apps
by changing one FINE-location permission flag (`0x100`) through an on-device Local ADB connection (wireless debugging).
User manual: `README.md`. Development history: `docs/CHANGELOG.md` and the `docs/Phase-*` / `docs/test.*` reports.

This public repository was created on 2026-10-09 from the private development repository (its `bluedotOFF/` folder),
which also holds research material and device logs that are intentionally not published. The release signing key lives
outside this repository in `../.private/` (read by `app/build.gradle.kts`): never read it into output, copy, commit or delete it.

The owner is not a professional developer and is happy with the current behavior.
**Preserve existing functionality.** Prefer small, reversible changes and verify with the full test command below.

## Build and verify (run from the repository root)

```powershell
.\gradlew.bat :core:test :app:testLocalDebugUnitTest :app:testLocalReleaseUnitTest :app:assembleLocalDebug :app:assembleLocalRelease :app:lintLocalDebug :app:lintLocalRelease
```

Expected on 2026-10-09: core 310 tests, app debug 289, app release 289, all passing; lint 0 errors, 42 warnings.
`scripts/finalize.py` and `scripts/package_source.py` write into `artifacts/`, which already holds the
delivered 1.0.0 APKs. Do not run them for a build you do not intend to deliver, or they overwrite
those files; bump `versionName`/`versionCode` in `app/build.gradle.kts` first.

## Rules that are easy to break

- **Translations use the Korean sentence itself as the key.** UI code passes Korean literals to
  `UiText.text()`, which looks them up in `app/src/main/assets/i18n/en.json`. If you change a
  Korean string in code, rename the matching key in `en.json` too, or English users silently see
  Korean. Strings with `{0}` placeholders are matched as patterns.
- Keep old `en.json` keys for messages that may already be stored in users' journals
  (`Entry.error`), e.g. the two legacy "UID …" messages kept on 2026-10-09.
- **Do not rename or remove `TargetState` enum values** (including `SHIZUKU_REQUIRED`). They are
  stored by name in the on-device journal (`JournalStore`), and `TargetState.valueOf` would fail on
  existing data.
- Error text shown to users goes through `UiText.error()`: English/internal diagnostics are replaced
  by a plain Korean message; Korean product messages pass through unchanged.
- Some core tests identify block reasons by a word in the message (e.g. `contains("공유")`,
  `contains("핵심")`). Keep those words when rewording `BlockReason` messages.
- Safety invariants (do not weaken): the only system mutation is
  `updatePermissionFlags(FINE, mask=256, value)` in `MinimalPrivilegedHelper`; the journal is written
  durably before any write; writes are re-checked immediately before applying; failures roll back
  and never report success. `scripts/finalize.py` asserts several of these by source pattern.
- The code style is dense (long single-line expressions). Match the surrounding style in small
  edits; a large reformat should be its own change with the full test run.

## Work log

### 2026-10-09 — Claude Code review cleanup (in the private repository, before this public repository existed)

- Friendly errors: added `UiText.error()` + `UiText.GENERIC_ERROR` and applied it where errors are
  displayed in `BlueDotUi.kt` (snackbar notice, batch result error, wireless warning, shared-group
  dialog error, app detail error). Test: `UiTextTest.internalDiagnosticsBecomePlainErrorGuidance`.
- Plain wording: removed jargon (UID, FINE, Legacy Shared UID, Local ADB, AppOps, firmware) from
  user-visible Korean messages in `Eligibility.kt`, `IndicatorEligibility.kt`, `Domain.kt`,
  `Discovery.kt`, `BinderBackend.kt`, `BlueDotUi.kt`; `en.json` keys updated to match (and a few
  new entries added). `UiRenderTest` updated for the renamed review label.
- Update-reminder check: no change needed. `LocalAdbApplication` registers `LifecycleReceiver`
  at runtime for `PACKAGE_REPLACED`/`PACKAGE_REMOVED`, so update reminders work while the app
  process is alive. Android does not deliver these broadcasts to manifest receivers, so nothing is
  sent when the process is dead; the app still detects updates on its next scan.
- Removed unused code (no references in app code or tests): `AppOpsHistory.lastAccess`,
  `BinderBackend.OFFICIAL_SIGNER`, `BinderBackend.recordDiscoveryFailure`,
  `JournalStore.archiveForNewInstall`, `UiModel.allApplied`, `Snapshot.supportsHide`,
  `LocalAdbDiscovery.permissionDeniedProbe` (debug-only probe), `NativeAdb.socketPermission`
  and its JNI function in `native_adb.cpp`.
- Scripts: replaced about 50 per-version copies with `finalize.py` (version read from Gradle) and
  `package_source.py` (from the hash-audited Phase 8.0 packager). Kept `check_source_delivery.py`,
  `export_guide_svg.py`, `preview_sheet.py`, `prepare_signing.py`. Old scripts remain in Git history.
- Docs: the old README moved to `docs/CHANGELOG.md`; a new user-manual README was written.
  Root-level report copies that were byte-identical to `bluedotOFF/docs/` were removed.
  `Phase-7.3-report.md` at the root differs from the docs copy and was kept.
- US models: `MinimalPrivilegedHelper.verify()` no longer blocks CSC `US`. `LocalAdbSession.rememberRegion()` stores
  `ui-state`/`usModel` from the PROFILE result, `UiModel.usModel` drives a warning card at the top of Home
  ("미국 판매 모델 안내"). Reason: on US CSC, SystemUI enables an extra location display path
  (`ScRune.QUICK_SUPPORT_LOCATION_PRIVACY_CHIP`), so the 0x100 flag alone may not hide the dot. Not tested on a US device.
- Language: any non-Korean locale shows English (`UiText.text`). Added ~31 missing `en.json` entries; only preview demo
  data and the composed `rejectionReason()+" 변경된 내용은 없습니다."` notice remain partly untranslated.
- Compatibility is checked by method bytecode hashes (`assets/reference-*.tsv`), not by model name. Only Fold7
  (SM-F966N, One UI 9) is verified; other models pass only if those framework/SystemUI pieces are identical.
- Finding (2026-10-09, Fold7): Toss (`viva.republica.toss`) loses the hide within minutes because it toggles its own
  component (`setEnabledSetting`, obfuscated `o.getErrorMessage`, states 1→2→1) in the background. That sends
  `PACKAGE_CHANGED`; AOSP `PermissionPolicyService` reacts with `updateUserSensitiveForApp(uid)`, which recomputes
  `USER_SENSITIVE_WHEN_GRANTED` (0x100) back ON. Observed: reapply 21:06:18, Toss toggle 21:13:51, flag ON 21:13:55,
  no user interaction. Boot also recomputes all apps. No code change was made for this.
- Welcome screen: "미리 알아두세요" card (3 bullets) warns that some apps lose hiding within minutes and must be
  reapplied from Home. Lines use `LineBreak.WordBreak.Phrase`; Robolectric renders char-wrapping regardless.
- Bottom navigation is transparent (`NavigationBar(containerColor=Color.Transparent)`) with a thin divider, so it follows the
  blue/system page background instead of floating as a gray block. Render test: `86-home-empty-blue-dark`.
- Release prep: Apache-2.0 `LICENSE`; README disclaimer, install (Releases), Issues contact; in-app recovery help now points
  to GitHub Issues. Release build uses R8 (`isMinifyEnabled`, `isShrinkResources`, `app/proguard-rules.pro` keeps
  `MinimalPrivilegedHelper.main`, which is launched by name). Release APK 20.5 MB -> 3.8 MB. Verified on Fold7 with the
  R8 release re-signed by the debug key: helper start, full read, and Toss reapply. Version stays 1.0.0 (versionCode 27).
- Fix (2026-10-09, found in owner device test of the R8 build): an uninstalled registered app (`MISSING_TARGET`)
  stopped "모두 복원" at that app (24 of 94 restored, rest untouched) and made scope lowering fail, because the
  batch/preview read a package that no longer exists. `MainActivity.perform` now skips `MISSING_TARGET` when
  restoring (nothing to restore: Android drops the permission state with the app), and `previewScope` ignores them.
  Per owner feedback they are hidden from all screens (`UiModel.visibleEntries` excludes `MISSING_TARGET`); their
  journal records stay intact. They no longer block "적용완료" or count as "상태 확인 필요".
  The final scan step shows "N개 앱 확인 완료 · 마무리 중…" instead of sitting at 99%.
- Robustness (same root cause): a multi-app restore (restore all, Home edit) now skips apps that cannot be restored right
  now - uninstalled, reinstalled (`INACTIVE_INSTALL`), permission revoked (`UNSUPPORTED_STATE`), `FAILED_ROLLBACK`,
  `SHARED_UID_*`, `NEEDS_REVIEW` (the same set `batchHide` already excluded) - instead of stopping at the first one.
  A blocked shared-group member skips its whole group. A single explicit restore still shows the exact reason.
  Logic: `UiModel.restorePlan` (tests in `UiModelReapplyTest`). Still stops by design (safety): connection loss,
  leaving the screen, or an app whose flag/permissions change during the 1.5 s verification (e.g. Toss resets).
- History: the fast re-check on returning to the app (inventory cache < 5 min) now also re-reads location access times
  (`BinderBackend.refreshHistory`, display only, ~1 s, same connection), so apps used meanwhile appear at once.
- Wireless lease (`WirelessWork.lease`): right after re-enabling, discovery could return the previous adbd port; the
  socket connect failed (`ADB_CONNECTION_FAILED`) and the app showed "무선 디버깅을 직접 꺼주세요". Now a connect
  failure re-discovers a fresh port once, and a failure before any socket opened disables wireless without a warning.
  `W/bluedotOFF` logcat lines record why a lease failed (diagnostic, no personal data). Verified on Fold7.
- Picker: already applied registered apps show "적용중" and are not selectable (`UiModel.canSelect`); rows that cannot
  be acted on ("확인이 필요한 앱", "현재 적용할 수 없는 앱") are no longer listed; selectable rows come first.
  In the whole scope, shared-UID apps are directly selectable (`canPickGroup` -> `MainActivity.pickGroups`): one
  connection reads and validates the group, then the whole group is selected; failing groups are hidden
  (`UiModel.rejectedGroups`). Direct actions (history "숨기기") keep the member-list confirmation dialog.
- Pre-publish review fixes (GPT review 2026-10-10): `scripts/package_source.py` now packages and requires `LICENSE` and
  `app/proguard-rules.pro` (the source ZIP failed `minifyLocalReleaseWithR8` without it); failed shared groups are
  retried after any refresh/scope/package change (`invalidateScan` clears `rejectedGroups`); "전체 선택" label and
  checkbox use the same enable condition. Settings > 고급 설정 > "오픈소스 라이선스" shows the bundled notices.
- Settings/Home (owner request 2026-10-10): "재적용 알림" is a switch (pref ui-state/reapplyNotify, honored by ReapplyNotification; turning on asks for notification permission); the "연결 관리" status row and "앱 삭제 전 모두 복원" left Settings. Home "재적용 확인" became "앱 복원" (dialog: 모두 복원 / 골라서 복원 -> edit mode); reapply stays on the "N개 앱 · 재적용 필요" line and the picker. Bottom of Settings is one "정보 및 지원" list: 도움말 · 문제 해결, 고급 설정 (expands), 오픈소스 라이선스, 버전.
- Final review fixes (GPT review 2026-10-10): `CMakeLists.txt` adds `-ffile-prefix-map=<cpp dir>=.` to all native targets
  (BoringSSL stored 144 absolute build paths via `__FILE__`; `finalize.py` now asserts none remain). The reapply switch
  also reflects the "reapply" notification channel (`MainActivity.reapplyNotificationsEnabled`, blocked = off; not created
  yet = on); switching it on while only that channel is blocked opens the channel's system settings.
  Help ("적용이 안 될 때") now says reminders cannot report app updates while bluedotOFF is fully closed.
- Not done (suggested later): splitting `MainActivity`, reformatting dense code, moving i18n to
  `strings.xml`, tidying the root research folder (`bluedotOFF_audit.zip`, `bluedotoff_filelist.txt`).
  Not device-tested in this session.
