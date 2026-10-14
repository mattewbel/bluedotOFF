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
- History (owner request 2026-10-13, 1.0.3): ↻ next to the title (`refreshHistory`: Check.HISTORY now, bypassing the 1-minute limit); each row is clickable (ripple) and opens the detail page; `UiModel.detailBack` makes Back return to History. Device-tested on Fold7. Resident helper ("lab mode") is planned for 1.1.0 after the owner's all-day survival test.
- 1.0.4 (versionCode 31, owner request 2026-10-13, from the 1.0.3 review): diagnostics "Compatibility check: passed" only when a helper confirmed `abiVerified` in this process (`LocalAdbSession.compatibilityConfirmed`); "앱 삭제 전 복원" carries `beforeUninstall` in `BatchRuntime`/`BatchOutcome` so the result dialog survives Activity recreation (fold); auto re-apply is armed in onResume and spent by the first check that finishes afterwards (`autoReapplyArmed`); the reapply notification opens Home when auto re-apply is on; Back works during the quiet auto re-apply; snackbar notices sit above the bottom navigation (measured height via `onSizeChanged`, test in `noticeShot`); README notes some banking/payment apps refuse to run with Developer options on. QA pass: notices also clear a page's pinned bottom buttons (`Modifier.bottomActions()` reports their height; test `noticeSitsAboveThePageButtons`); `handledBatchId` and `uninstallCheck` are saved in the instance state (same pid only) so a fold does not re-show the last notice/dialog; bottom tabs work during auto re-apply; the automatic-management switch cannot be turned off while auto re-apply writes. Not changed on owner request: the experimental `app.bluedotoff.helperpoc` stays installed.
- Owner decision (2026-10-13): 1.0.4 stays internal (not pushed, no GitHub release). Its changes ship with 1.1.0: fold the 1.0.4 CHANGELOG section and `artifacts/release-notes-1.0.4.md` into the 1.1.0 notes, and update the APK file names in README.md/README.en.md to 1.1.0. The public repo has unpushed 1.0.4 commits (`db6fbab`, `91ef6d7`) that go out with 1.1.0.
- 1.1.0 groundwork (Claude Code, 2026-10-13, branch `claude/1.1.0-groundwork` from `52e0b56`; versionName 1.1.0 / versionCode 32).
  Claude Code built only the parts of "백그라운드 감시(실험)" that do not involve the privileged helper; the watching itself
  (helper side) is Codex's task. Done, all in `app/src/main/java/app/bluedotoff/WatchSupport.kt` unless noted:
  - Settings switch "백그라운드 감시(실험)" (pref `ui-state/backgroundWatch`, default off; `UiModel.backgroundWatch`,
    `MainActivity.backgroundWatchChanged`). The first switch-on shows `WatchRiskDialog` (BlueDotUi.kt): 5 risks, confirm unlocks
    after a visible 5-second countdown ("확인 (N초)" + progress bar), cancel; confirming sets `ui-state/backgroundWatchAcknowledged`.
    Right now the switch only stores the choice and logs it - it does not watch anything yet.
  - `BackgroundWatch.enabled/acknowledged/set/running`. Call `running(context)` whenever watching (re)starts.
  - `WatchNotification.stopped(context, Reason.BOOT|USB_DEBUGGING_OFF|STOPPED)`: channel "watch", once per stop (pref
    `watchStopNotified`, cleared by `running()`), only while the switch is on; text ends with "다시 켜려면 Wi-Fi에 연결된 상태에서
    bluedotOFF를 열어주세요." `LifecycleReceiver` already calls it with BOOT on BOOT_COMPLETED.
  - Test log (`TestLog`, `ACTIVE=true`; set false or remove before any public release): `filesDir/test-log.txt`, 4 MB cap
    (keeps the newer half), line format `yyyy-MM-dd HH:mm:ss | kind | message`. Kinds in use: `battery` (level, chargeUah,
    charging, screen, watch), `state` (usbDebugging / wirelessDebugging / wifi, logged when changed between samples), `watch`
    (switched on/off, running, stopped: REASON), `unhidden` (`pkg=<p> source=open-check`; helper detections should use
    `source=watch`), `result` (`pkg=<p> mode=auto|hide|restore|show ok=true|false [error=...]`, written by MainActivity for every
    batch; background re-applies should log `mode=watch`), `system` (boot completed). `TestLogReceiver` (exported=false) samples
    every ~10 min via a non-waking inexact alarm armed in `LocalAdbApplication.onCreate` (so the log itself does not wake the phone;
    samples can be sparse in deep sleep, deltas stay correct). Settings "테스트 로그 내보내기" saves via the system file picker
    (SAF) with `TestLogSummary` on top: unplugged drain per hour (% and mAh) for all / screen off / watch on / watch off,
    per-app unhidden counts and average interval, result counts, watch stops. Pure summary tests: `TestLogSummaryTest`.
  - Tests: `UiRenderTest.backgroundWatchAsksFirstWithAFiveSecondWait`, `backgroundWatchDoesNotAskAgainOnceAcknowledged`,
    `UiTextTest.backgroundWatchTextsHaveEnglish`; en.json has all new strings. Full suite: core 319, app 332 x2, lint 0 errors.
  - Not device-tested yet (phone was not connected). The release APK from `assembleLocalRelease` is signed with the release key
    (same signer as the installed app), so it installs as an update with `adb install -r`. Never uninstall the installed app:
    that deletes the journal (restore records).
  Left for Codex: the watching itself and its wiring (start/stop with the switch, `running()`, `stopped(...)`, `unhidden`/`result`
  logging with source/mode `watch`), stop watching before "앱 삭제 전 복원", never re-hide apps the user restored or unregistered,
  replace an old helper after an app update, one operation at a time with the app, more precise state-change logging if the
  helper can report it, README/CHANGELOG/release notes for 1.1.0 (fold in the 1.0.4 notes), and the owner's device install.
- 1.1.0 watch body (Codex, 2026-10-14, continued on `claude/1.1.0-groundwork` / baseline `01d9bb4`; test only, no push or device install):
  - Connected Claude's existing switch/risk dialog/stop notification/test log. Added `adb/ResidentWatchHelper`, `WatchBridgeProvider`,
    `WatchClient`, `WatchProtocol`, core `WatchPolicy`/`WatchEngine`. Initial start goes through existing TLS Local ADB; a detached
    shell UID 2000 helper reuses the original typed `MinimalPrivilegedHelper` dispatcher/setter via Binder. USB debugging stays
    user-controlled and must be ON. A fresh helper still requires the normal Wi-Fi/wireless bootstrap; it cannot be launched
    from an ordinary app merely because USB debugging is enabled. No root, network listener, generic command/Binder proxy,
    extra foreground service, or Shizuku dependency. Provider accepts shell only; daemon checks caller UID/signers/current APK,
    version and lastUpdateTime on every request. Old helpers exit after own-package updates and are replaced on an app visit.
  - Events use `PackageManager.registerPackageMonitorCallback(IRemoteCallback,userId)` and optional permission changes,
    with 750ms/5s coalesced checks; newly configured targets also get those two bounded checks for delayed resets.
    `registerReceiverWithFeature(caller=null,...)` was rejected as an approach: current AOSP requires a registered ApplicationThread.
    Runtime generated Stub transaction names are validated instead of reading restricted TRANSACTION fields or guessing numbers.
    IProcessObserver re-delivers the Binder on a client foreground transition; external provider calls can reconnect an ordinary
    dead app process for an event. A 60s self-install/client-state heartbeat is not a whole-app/permission polling loop.
  - Only verified, enabled APPLIED/NEEDS_REAPPLY_POLICY/UPDATE entries (and complete registered shared groups) may be revisited.
    Fresh journal, install, firmware, boot, scope and non-sensitive invariants are checked; restored/unregistered/reinstalled,
    pending/failed/review entries are excluded. Existing SafetyEngine/SharedGroupEngine fsync/preflight/0-500-1500ms/rollback
    are reused. LocalAdbSession.exclusive serializes foreground Local ADB, resident work, and stop; off blocks new work and
    drains current verification before exit. Deferred events are reconsidered after a foreground batch, against latest records.
    App-uninstall preparation turns monitoring off before the old restore flow. No resident self-permission grant/revoke.
  - Foreground read/manual apply/restore reuse a live resident backend without Wi-Fi; missing helpers use the existing workflow.
    Running/stopped hooks wired; successful watch results cancel the app's old reapply notice. Test log adds actual running status,
    state changes, callback support/startup failure stage, watch detection/results, version/journal-state export header and separate
    mode/source summary. No location contents, pairing keys or tokens; ACTIVE=true remains test-only. Battery numbers are device-wide
    estimates with non-waking approximate samples, not monitor-only consumption.
  - QA: core 338, app 343 debug + 343 release; full Gradle command passes, lint 0 errors / 68 warnings (includes hidden API warnings).
    Added restore/reinstall/scope/invariant/durable-failure/rollback/shared-UID guards and Binder routing/install-replacement/stop
    serialization tests. R8 entrypoint retained, release signer matches existing public certificate (619fe1ec...ceaad62).
    Checks: `bluedotOFF/artifacts/1.1.0-watch-build-check.json`. Read `docs/1.1.0-watch-validation.md` for tested vs pending matrix.
  - README/README.en/CHANGELOG updated, 1.0.4 notes folded into 1.1.0. `artifacts/release-notes-1.1.0.md` explicitly INTERNAL TEST ONLY.
    Signing material was not manually read/copied; existing Gradle signing is unchanged. Antigravity public-source research failed
    on sandbox authentication access, so no output was relied on; Codex verified AOSP directly. The public checkout is untouched.
  - Still pending: NEW implementation device startup, actual Samsung callback delivery, hide/restore, unplug/sleep/process death,
    Auto Blocker/reboot/update smoke checks, and owner's 14h test. Previous separate PoC's USB-on survival/read success is NOT a
    device pass for this implementation. Install only after owner says connected/ready: check unlocked, signer, then adb install -r;
    never uninstall/reset data, and stop on signer mismatch. Test logging must be reviewed before any public release.
  - Owner connected Fold7 on 2026-10-14: unlocked verified, installed 1.0.4/code31 and new APK signers matched; updated via
    adb install -r to 1.1.0/code32. Installed APK SHA-256 matches final build (78cd0f72...fe7141). No uninstall/reset/pairing wipe.
    USB ON, wireless OFF, Wi-Fi ON read back. Monitoring switch/consent not operated on owner's behalf; actual new-helper
    runtime verification and 14h usage remain pending. See docs/1.1.0-watch-validation.md for installation evidence.
  - Owner returned with usage logs on device-date 2026-10-14: exported log with explicit approval to open app (auto reapply may
    run); no switch/direct hide/restore operated by reviewer. Watch ran 07:56:24~19:08:32 (11h12m; total log 11h30m, NOT 14h).
    Same shell helper PID across 5 attachments and still alive before app open, USB ON/wireless OFF/Wi-Fi OFF; mobile-data
    preference ON. Watch detections/results: Toss 31 + Google app 2, all 33 successful, zero logged failures/stops; startup
    open-check auto results 5 separate. Owner Google Maps restore/hide results succeeded at 11:54. This confirms real new
    implementation operation, not just PoC. 28/33 detection-to-verified-result <=2s, median2s; outliers11/20/44/58s unresolved.
    Actual reset-to-detection/setter timing and visible privacy dot not captured. APPLIED213 export count is saved journal,
    not proof of every live OS flag. Battery total6.45%/h is whole device, no watch-off control and six charging=false increases;
    do not attribute it to monitor. Helper cumulative CPU21s is supporting observation only. Raw logs/analysis/report under
    ignored research/watch-110-results-20261015 (directory date differs from actual log date); no private logs delegated.
    Remaining: latency instrumentation, charging/power comparison, uninstall/USB-off/Auto Blocker/reboot/update regressions.
- 2026-10-14 follow-up: History adds Material3 pull-down refresh (no dependency change) and REAPPLY tab. Local display-only
    ReapplyHistoryStore keeps latest200 watch/app-open/manual reapply results, imports previous test-log watch/auto once;
    excludes first-time add/restore/show and unstarted remainder. It never drives permissions or replaces the restore journal.
    Result logging is failure-isolated; partial JSONL tails are separated, invalid rows skipped. Local tab refresh needs no ADB,
    duplicate refresh blocked; fold/recreation reloads and known labels update without inventory scans. Korean/English/UI docs updated.
    Luna handled bounded UI/tests and independent review; main integrated and checked actual changes (no measured token claim).
    Owner concurrently supplied Claude's restore-first beforeUninstall/stopWatchForUninstall flow; PRESERVED. This supersedes
    earlier stop-before-restore notes: live helper restores with Wi-Fi OFF, then outcome handler stops watch/helper. Added tests
    for resident route before stop, failed restore remaining count, verified completion then STOP, UI drag/offline/recreation,
    storage cap/corruption/IO failure. All automated: core338 + app357 debug + app357 release =1052 pass, lint0 errors/68 warnings.
    Final source full Gradle command passed; release signer unchanged, new APK SHA ba657d80...ebe46f43e (4021109 bytes).
    Audit: bluedotOFF/artifacts/1.1.0-history-build-check.json. New APK has NOT been installed/device-tested or publicly pushed.
- Owner requests (Claude Code, 2026-10-14 evening, uncommitted on `claude/1.1.0-groundwork` together with Codex's work):
  "백그라운드 감시(실험)" is now "백그라운드 감시" (switch, dialog title, channel text, en.json, README/CHANGELOG) and is the
  third Settings section (after 무선 디버깅 자동 관리; test `backgroundWatchIsTheThirdSettingsSection`). Test log collection is
  removed: `TestLog`/`TestLogReceiver`/`TestLogSummary`, the manifest receiver, the export button and `TestLogSummaryTest`.
  Watch state/failure notes go to logcat only (`WatchLog.event`, no package names); unhidden/result file logging is gone
  (ReapplyHistoryStore still records results). `LegacyTestLog.cleanup` (LocalAdbApplication) lets ReapplyHistoryStore import the
  old test-log.txt once, then deletes it, the "test-log" prefs and its alarm. "진단 정보 복사" is "진단 정보 복사 및 보내기":
  copies, then opens the mail app (ACTION_SENDTO to `SUPPORT_EMAIL` yundeokhee92@gmail.com, subject "bluedotOFF diagnostics
  <version>", body = the text); the user sends it; without a mail app it only copies. Full suite: core 338, app 353 x2, lint 0
  errors. Installed on the owner's Fold7 (adb install -r, signer 619fe1ec..., installed = built e8ff1cad...): Settings order,
  imported reapply history, no pending TestLogReceiver alarm and the Gmail compose (body filled) checked on device.
- Owner requests (Claude Code, 2026-10-14 night, uncommitted): welcome "미리 알아두세요" is 4 short lines (precise location first,
  USB watch, Auto Blocker). Setup step 9 (`SetupStage.AUTOMATIC_MANAGEMENT`, label "연결 유지 방법") offers "무선 디버깅 자동 관리 (권장)"
  or an outlined "USB 디버깅으로 유지 (백그라운드 감시)": View-based 5-second risk dialog (`BackgroundWatch.RISKS`, shared with Settings),
  then Developer options if USB debugging is off (`SettingsEntrypoints.developerOptions`); once step 9 shows again with USB on,
  `finishUsbChoice` turns the watch on and runs the same `enableManagement` (the permission restarts the watch after reboot/update).
  Owner chose "restart-only": while watching, the Settings automatic-management switch is locked (dialog explains), its consent is
  kept for restarts; "USB 디버깅 켜러 가기" shows when watching with USB off (`UiModel.usbDebugging`). Picker always says only
  "정확한 위치" apps can be hidden. Help adds a 백그라운드 감시 section; README (ko/en) moves it under Using the app in plain words.
  Guide `automatic` step updated. Full suite: core 338, app 354 x2, lint 0 errors. Device: not yet installed at the time of writing.
- Watch stop guidance (Claude Code, 2026-10-14 night): `WatchNotification.advice(usbOn,autoManaged)` gives the restart steps (USB debugging back on + Wi-Fi + open app; wireless debugging too without automatic management; no new pairing). Stop notice has a "USB 디버깅 켜기" action (Developer options) when USB is off; `BackgroundWatch.observeUsb` (LocalAdbApplication) posts "USB 디버깅이 켜졌어요" once per stop when USB returns while the app is closed; Home shows "백그라운드 감시가 멈춰 있어요" (`UiModel.watchRunning`). Help/README add the bank-app Developer options note. Full suite: core 338, app 357 x2.
- Owner device check (Fold7, 2026-10-14 ~21:20, build ab2fbfdd...): "앱 삭제 전 복원" restored all registered apps (Home "미적용", 72 apps show the indicator again); USB debugging off -> stop notice with "USB 디버깅 켜기", USB back on -> "USB 디버깅이 켜졌어요" notice, opening the app restarted the watch - all five steps confirmed by the owner. Watch restart after the app update was seen in logcat (wireless debugging on ~2 s, then off, watch running).
- Not done (suggested later): splitting `MainActivity`, reformatting dense code, moving i18n to
  `strings.xml`, tidying the root research folder (`bluedotOFF_audit.zip`, `bluedotoff_filelist.txt`).
  Not device-tested in this session.
