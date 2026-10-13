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
- Auto Blocker (2026-10-10, after 1.0.0 release; friend report + GPT research `research/autoblocker-20261010/`): Samsung
  "보안 위험 자동 차단" greys out USB/wireless debugging and makes AdbService turn ADB off (Fold7 device test: USB ADB dropped
  the moment it switched on). Its state (Secure `rampart_blocked_adb_cmd`, `rampart_main_switch_enabled`) is @hide and NOT
  readable by apps, even with WRITE_SECURE_SETTINGS (probe app, SecurityException). Observed instead: while it is on,
  `putInt(adb_wifi_enabled,1)` returns true but reads back 0 immediately; while off it stays 1. So
  `WirelessControls.enableWirelessDebugging` records that refusal in `AutoBlocker.suspected`; `WirelessWork.lease` then
  fails with `AutoBlocker.MESSAGE`, and setup shows "보안 위험 자동 차단을 꺼주세요" + "보안 설정 열기"
  (`SetupAction.SECURITY_SETTINGS` -> `SettingsEntrypoints.autoBlocker`). Manual users (no automatic management) cannot be
  detected, so the normal wireless step always adds "버튼이 회색으로 잠겨 있으면 먼저 보안 위험 자동 차단을 꺼주세요."
  One UI 8.5+ "자동으로 켜기" re-enables Auto Blocker 30 min after it is turned off. Help/README mention it.
  On Home the refusal is a centered dialog (not a snackbar) with two guide pictures (`GuideStep.AUTO_BLOCKER`,
  `AUTO_BLOCKER_OFF`, `assets/guide/steps.json`) and "보안 설정 열기"/"확인"; setup shows the same pictures. Test `87-auto-blocker-dialog`.
  Setup on a fresh install cannot detect it (no permission yet), so the wireless step always shows a tappable card
  "무선 디버깅 버튼이 회색으로 잠겨 있나요?" that opens the same guide (`LocalAdbSetupActivity.autoBlockerHelp`), and the welcome
  "미리 알아두세요" card has a 4th line about it. Owner device test of fresh setup with Auto Blocker on found the gap.
- Review pass (2026-10-11, owner asleep; only clear fixes): batch progress in CLOSING shows "N / N개 확인 · 마무리 중…" instead of
  "N / N · 99%" (bar still 99%); Help recovery sentence no longer says "다시 쓰지" (jargon). Guide pictures: ON/OFF drawn as
  switches, labels centred on glyph bounds, equal bottom spacing, boxed permission choices, fake "무선 디버깅 승인 취소" row removed.
  Suggestions (resume rescans, tone, dead gates, etc.) are in `bluedotOFF/artifacts/claude-review-20261011.md` (not in Git).
- Lighter rechecks (owner request 2026-10-11): `MainActivity.resumeCheck` no longer reconnects on every return. It reconnects
  only if Android reported a change (`changedPackages`, from PACKAGE_ADDED/REMOVED/REPLACED and now PACKAGE_CHANGED) for a
  registered app (then re-reads only those apps plus their shared-group members), if the last check is older than 10 min,
  or if the app is not ready. Inventory cache (`ScanStamp`) is 30 min (was 5). History re-reads only access times (at most
  once a minute); only the picker triggers a full inventory scan. Set-up users keep their screen during a check
  (`UiModel.rechecking`, small progress line) instead of the full-screen "연결 준비 11/11" view. Home shows "마지막 확인 N분 전"
  with a refresh icon (re-reads registered apps). Starting a write cancels a running read-only check. Safety re-reads before
  each write are unchanged. Device-tested on Fold7: return to app = no reconnect; History tab = 3 s read instead of a full scan.
- Removed dead gates MISSING/AUTHORIZATION/WRONG_MODE/ABI_FAILED (Shizuku era, never set). Home connection screen uses the
  same segmented 11-step bar as setup. Picker: already applied apps show a filled blue checkbox (disabled).
- Wording: UI copy unified to "~해요", "적용 중"/"적용 완료" spacing, "파란 위치 표시" instead of "Blue Dot"/"파란 점" in body text
  (the welcome slogan keeps "Blue Dot"). Core/journal safety messages (Domain, SharedGroup, JournalStore, BinderBackend, …) were
  left unchanged on purpose; renamed messages that may already be stored keep their old en.json key as well.
- Timing logs (I/bluedotOFF, durations only): "lease: …", "helper: startup/work/total", "window: N apps hide|restore".
- Fix (owner device test 2026-10-11): our own lease turning wireless ON made the endpoint watcher start a second check that
  failed once the lease turned wireless OFF ("무선 디버깅을 켜주세요"); with no enabled entries (after "모두 복원") that failure showed
  the connection screen. Now `WirelessWork.leasing` suppresses that trigger, background refreshes never connect under automatic
  management, and a failed check keeps Home when automatic management is ready. Picker label "이미 적용된 앱" -> "이미 표시 꺼짐".
- Owner requests 2026-10-11 (afternoon):
  - Speed: `BatchWindow.MAX_TARGETS` 12 -> 48 per helper process, `HideWindowOutcome.MAX_IN_FLIGHT` 4 -> 12. Every target still
    gets immediate/500 ms/1500 ms checks; pipeline tests now scale with the constants. Baseline before: full restore of ~110
    apps took 116 s (12-app windows: helper start 0.8-1 s + ~9.5 s work).
  - Large groups: picking a shared group with >= 10 members or a system UID (< 10000; e.g. 디바이스 찾기 = android.uid.system,
    93 apps) asks first (`UiModel.bigGroups`, dialog "큰 앱 묶음을 함께 선택할까요?"), both for single taps and "전체 선택".
  - Hold: main-screen leases (`hold=true`) keep wireless debugging on while a bluedotOFF screen is visible; `WirelessWork.release`
    turns it off 2 s after leaving (and 5 s after process start if a previous process died while holding, pref heldOn).
    Setup leases never hold. Device-tested: one ON at open, OFF 2 s after Home.
- Speed, measured first (I/bluedotOFF "perf:" line per helper; `Perf` counters): 20-app restore = 9.9 s work, of which journal
  commits 7.3 s (184 ms each: parse ~67 ms before + full rewrite + fsync + parse again) and journal reads 1.0 s; target reads
  0.5 s; RPC 0.7 ms per call. So `JournalStore` now reuses the last parsed/written list while the file stamp (mtime, size) is
  unchanged (only this class writes it), verifies a write by comparing the stored bytes with the serialized bytes, and reuses
  each unchanged entry's JSON text. Fsync + atomic rename before any permission write are unchanged. After: 20-app add 4.8 s
  (commit 70 ms); entry-text reuse not yet measured. Consecutive shared groups reuse one helper (up to 8).
- Restore selection is its own page (`Page.RESTORE`, same layout as 앱 추가: search, 전체 선택, checkboxes, "N개 복원").
  Home inline edit mode and `UiModel.editing` were removed; "골라서 복원" and the Home "편집" link open the page.
- Measured after the journal changes: 20-app restore 4.15 s work (was 9.9 s), 20-app add 4.1 s; commit 44 ms. What is left is
  mostly the 500/1500 ms settle waits. RPC bundling (GET_TARGET_SNAPSHOT idea) would save < 1 s per 20 apps; not done.
- Shared groups: groups with no member declaring precise location (eligibility INELIGIBLE_OP for all same-UID apps) are no
  longer offered (`UiModel.groupMayShowIndicator`); if one still fails with `SharedGroupPolicy.NO_LOCATION` it is skipped
  silently. Other failures are listed in a dialog with reasons (`UiModel.rejectedGroupList`) instead of a snackbar count.
- Snackbar notices: shown 5 s in their own coroutine scope. Before, clearing/replacing `model.notice` restarted the
  LaunchedEffect and cut the snackbar off after about a second.
- 1.0.1 (versionCode 28), GPT release review 2026-10-11 fixes: `WirelessWork.lease` clears `leasing` in a finally around the
  whole lease (a refused start used to leave it set and silence the endpoint watcher; test
  `LocalAdbSessionTest.refusedWirelessStartReleasesTheLeasingFlag`); automatic-management copy now says the connection stays
  while the app is in use and turns off shortly after leaving (UI, setup, guide, README, en.json); `JournalStore` reuses its
  parsed list only while the file bytes equal the cached bytes (same-stamp corruption is now rejected; test
  `corruptionWithTheSameStampIsNotServedFromCache`).
- Kept ADB connection (owner report 2026-10-11): Android posts "무선 디버깅 연결됨" for every new ADB connection, not when
  wireless debugging is switched on, so holding the switch alone still alerted on each task. Main-screen leases now keep the
  authenticated `LocalAdbConnection` when every helper exited orderly (`LocalAdbSession.held`, `CloseReceipt.kept`); the next
  task reuses it; a dead kept connection is replaced only if no helper started yet. `WirelessWork.release` closes it
  (`closeHeld`) before turning wireless debugging off. Device log: second task "connection: reusing the kept connection".
- Kept connection fixes (GPT review 2026-10-11): `WirelessWork.release` closes the kept connection for every user, not only
  with automatic management (it used to return early when the heldOn marker was not set). Turning automatic management
  off while the app is open runs `releaseNow` first, so wireless debugging the app turned on is switched off before consent
  is withdrawn. Tests: `KeptConnectionTest` (adopted from the review probe).
- Recheck timer (GPT final review 2026-10-11): a partial check of changed apps reset the 10-minute full-check timer, so an app that changes often (Toss) could postpone the other apps' recheck forever. `RecheckClock` (MainActivity.kt) now resets only on a check of all registered apps, and an expired timer wins over a partial check; "마지막 확인" shows the last full check. Test `RecheckClockTest`.
- Progress wording (owner request 2026-10-11): the batch overlay said "확인이 끝나면 결과를 알려드려요." as its main line, which read as if leaving were fine; leaving stops the remaining apps. It now always says "끝날 때까지 이 화면을 열어 두세요. 앱을 나가면 남은 앱은 바꾸지 않아요." (the separate small line was removed).
- "표시 켜기" (owner request 2026-10-11, GPT-reviewed design): removing bluedotOFF deletes its journal, so apps it hid stay OFF with no original; registering them recorded OFF as the original and Restore kept them OFF. Such apps (`UiModel.leftOff`: `PickerPolicy.alreadyNotSensitive`, not managed, latest read OFF) are no longer pickable ("표시 꺼짐 · 기록 없음"); Home shows "위치 표시가 꺼진 채 남은 앱 N개" -> `Page.SHOW` (single apps only; shared groups listed; "전체 선택" takes user-installed apps only; confirm does not promise a visible dot). Core: `SafetyEngine.userShow/userShowWindow` journal `TargetState.PENDING_SHOW` (appended; originalBit 0 = value before, target ON) before the write, same 0/500/1500 ms checks, failure rolls back to OFF; success = NOT_APPLIED, enabled=false, originalBit=0x100, so a later hide restores to ON. `Reconcile` resolves an interrupted show from the real bit. Helper unchanged. Device (Fold7): Naver Map OFF -> 표시 켜기 -> FINE flag ON; hide -> OFF; restore -> ON; 3-app show force-stopped mid-way -> all ON, reopened with no stuck records. Screenshots do not capture the privacy dot here, so the visible dot was not verified by the agent.
- 다시 켜기 fixes (GPT review 2026-10-11): renamed to "파란 위치 표시 다시 켜기" (Settings row, page title) / "다시 켜기" (action). `canShow` allows a verified singleton shared-user app (KakaoMap kind; `sharesUid` = real multi-app UID only). `leftOff` uses the latest Snapshot when present (inventory `indicator` can predate a restore to an original OFF). Home card only when `usedBefore==true`, or unanswered with journal records; a fresh install with such apps is asked once on Home ("이 휴대폰에서 bluedotOFF를 사용한 적이 있나요?", pref ui-state/usedBefore). Tests: UiModelShowTest, SafetyEngineTest singleton show.
- Home card "파란 위치 표시가 꺼진 앱 N개" has "닫기" (owner: it showed every time); closing is permanent (pref ui-state/leftOffCardClosed), Settings keeps the entry. Device-tested: closed, force-stopped, reopened -> stays closed.
- Owner decision (2026-10-11, supersedes the two entries above on naming/first-use/home card): no first-use question and no Home card; the feature lives only in Settings as "숨김이 남은 앱 (재설치 시에만)" -> page "숨김이 남은 앱", rows "숨김 남음 · 기록 없음", action "숨김 풀기". `usedBefore`/`leftOffCardClosed` removed (old prefs keys on devices are ignored).
- Final 1.0.1 copy (owner): welcome first line "앱은 최소한의 권한으로 작동해요. 그래서 일부 앱은 스스로 설정을 바꿔서 숨김이 짧은 주기로 풀릴 수 있어요."; Settings reapply notification adds "절전 모드에서는 알림이 오지 않을 수 있어요." (README/CHANGELOG updated).
- 1.0.2 (versionCode 29, owner requests 2026-10-11): the top header row (brand + dot) only on Home and while not set up (Gate != READY); other pages start with their own title (HEADER "홈" button only on Help during setup). Bottom navigation icons share a 24dp frame (`NavIcon`: Home = blue dot, History = drawn clock, Settings = material-icons-core outlined gear, no new dependency) so labels line up. Home no longer repeats "bluedotOFF" under the header ("적용 완료", "미적용", "N개 앱 적용 중", status "적용 중"; batch summary "적용 N개 · 확인 필요 M개"). English "Last checked just now" (lowercased age). README screenshots retaken (ko + en), English before/after image `docs/screenshots/before-after-en.png`.
- 1.0.2 re-release (owner decision, 0 downloads at the time): snackbar notices use the theme colours (surfaceContainerHighest + onSurface, outlineVariant border; Material default was inverse) and close on a tap. Tag `1.0.2` moved to the new commit and the release APK replaced. Tests: noticeUsesTheLightThemeAndClosesOnTap, noticeUsesTheDarkTheme.
- 1.0.3 (versionCode 30, from GPT review 2026-10-13; owner chose one release, no stronger Home warning): `LifecycleReceiver` exported=false (finalize asserts it). Settings "앱 삭제 전 복원" (`MainActivity.beforeUninstall`: restoreAll, then `UiModel.uninstallCheck` = apps still changed; 0 -> "앱 정보 열기"); no revoke/pairing wipe because uninstall removes both. Settings "진단 정보 복사" (`copyDiagnostics`: device, Android/build, One UI + country remembered from the helper PROFILE in `LocalAdbSession.rememberRegion`, gate, scope, counts; no app list). `finalize.py` writes `bluedotOFF-<v>-checksums.txt` (APK + signer SHA-256). README security summary, tested-devices table (Fold8 SM-F971N user report), `.github/ISSUE_TEMPLATE/report.yml`.
- Auto re-apply (owner request 2026-10-13, 1.0.3): the old "writes only on a tap" rule is widened to "when the user opens the app". `MainActivity.maybeAutoReapply` runs once per visit (`autoReapplyTried`, reset in onStop) after a refresh job completes (`invokeOnCompletion`), only with ForegroundState.active, Gate.READY, pref ui-state/autoReapply (default true) and automaticReady(); targets = `UiModel.reapplyTargetPackages` (NEEDS_REAPPLY_POLICY/BOOT/UPDATE; firmware recheck, review/failed and reinstalled apps excluded). `perform(...,auto=true)` (BatchUiState/BatchOutcome.auto) hides the overlay and shows "자동 재적용 중 · c / t"; outcome notice "N개 앱 자동 재적용 완료". Same write path and safety checks. Reapply notification text says opening the app re-applies when this is on. Device (Fold7): unlock -> check -> "window: 8 apps hide" -> notice. Diagnostics show One UI as "9.0 (90000)".
- Not done (suggested later): splitting `MainActivity`, reformatting dense code, moving i18n to
  `strings.xml`, tidying the root research folder (`bluedotOFF_audit.zip`, `bluedotoff_filelist.txt`).
  Not device-tested in this session.
