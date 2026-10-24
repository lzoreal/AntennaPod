# Project Context
AntennaPod is an open-source podcast application for Android, written in Java and built with Gradle.
This fork carries an ongoing transcript feature (bilingual sentence display, per-media time offsets, a floating transcript window and on-device verification); `docs/transcript-handover.md` is the entry point for that workstream.
`CLAUDE.md` includes this file (`@AGENTS.md`); keep this file as the single source of truth for all agents.

# Tool Use
Before editing a file, always read it first. The user might have updated it; if the user changed a file, that change has absolute priority.
When running git commands, disable the pager.

# Coding Style
Never fix any warnings outside the code you wrote.
Only update existing code if necessary to fulfill the user request.
In general, keep your changes as focused as possible and change the absolutely minimal number of lines.
Never reorder or reorganize the code unless asked for by the user.
Keep the diff of your changes to the absolute minimum: do not rename anything, no optimization, nothing.
Just the bare instructions from the user.
Do not ask for permission before making initial code changes.
Do not remove comments that are already in the code.
Whenever you add a user-visible string, add it to `:ui:i18n` so it can be translated. Do not start new strings files anywhere else.
Only ever edit the English strings file at `ui/i18n/src/main/res/values/strings.xml` and the Chinese strings file at `ui/i18n/src/main/res/values-zh-rCN/strings.xml`. Never modify other translated strings files under `values-*/`.
Every string added to the English file must also get a Chinese translation in `ui/i18n/src/main/res/values-zh-rCN/strings.xml`.
Never reference the full package name of classes directly in the code, use imports.

# Architecture
AntennaPod uses a highly modularized Gradle architecture, organized by domain. `settings.gradle` is authoritative; the main modules are:
- `:app` / `:app-wearos` — main Android app and Wear OS app; integrate all features.
- `:event` — EventBus events for cross-component communication.
- `:model` — core data classes (`Feed`, `FeedItem`, `FeedMedia`, `Chapter`, `Transcript`).
- `:system` — crash reporting, package and thread utilities.
- `:net:*` — `:common`, `:discovery`, `:download:service-interface`/`:download:service`, `:ssl`, `:sync:*`.
- `:parser:*` — `:feed` (XML feeds), `:media` (media tags), `:transcript` (episode transcripts).
- `:playback:*` — `:base`, `:cast` (Chromecast), `:service` (Media3 playback service).
- `:storage:*` — `:database`, `:database-maintenance-service`, `:importexport`, `:preferences`.
- `:ui:*` — shared UI and screens (`:common`, `:episodes`, `:preferences`, `:statistics`, `:i18n`, `:transcript`, `:widget`, ...).
Cross-cutting conventions:
- Several functional areas split a service-interface and a service module; consumers depend on the interface, and the implementation is registered at app startup via `ClientConfigurator`.
- Use EventBus (`:event`) for cross-component events, `:storage:preferences` for settings, `:storage:database` for DB access, and `:playback:service` for playback/Media3.
- Keep module boundaries; do not reach into another module's internals when a shared module already exists.

# Running and Testing
After you are sure that the code is correct, ensure that there are no compilation errors (warnings are okay).
To compile the code, use `./gradlew :app:assembleDebug`.
You are already in the main directory of the project so there is no need to `cd` into the main folder.
The CD command is strictly forbidden. Always assume that you are already at the main folder of the project.
Never alter or filter the compile output in any way (no grep, head, tail, or any other command that truncates output).
You will lose critical information and the results will not be helpful.
Always look at the complete compiler output.
DO NOT MAKE UP YOUR OWN COMPILE COMMANDS, THEY WILL NOT WORK!
ONLY USE THE EXACT COMMANDS GIVEN IN THIS FILE!

Only then run the application or the tests to verify it.
Usually you will need to run the application, but if there are existing tests that cover the code you wrote, you can run those instead.
Only run the tests or device-test scenarios that cover the code you changed; do not re-run scripts for features you did not modify.
For installing and running the application, use the command
`./gradlew --console=plain :app:installPlayDebug && adb shell monkey -p de.danoeh.antennapod.debug 1`.
If needed, you can grab a textual representation of the screen using `adb shell uiautomator dump /sdcard/ui.xml; adb shell cat /sdcard/ui.xml`.
You can even control connected devices using `adb shell input tap <x> <y>` and `adb shell input swipe <x1> <y1> <x2> <y2> <duration ms>`.
If there is a crash, read the logs using `adb logcat -d | grep "de.danoeh.antennapod" | tail -20` and fix the issue.
For running tests, use the command `./gradlew --console=plain` and use the task `:test` of the relevant module.
As a final style check before opening a PR (or if a user explicitly asks for it), check the code style using:
`./gradlew checkstyle lint`.

# PR Conventions
When creating a PR, always read the PR template at `.github/pull_request_template.md` before starting and strictly follow it.
The description goes above the checklist.
Always mention the corresponding issue using `Closes: #<number>` in the description.
Keep the description minimal, using 2-8 sentences.
Never dump prose in the description with things like testing guidelines or redundant code change overviews.
Never change the PR title unless explicitly asked to do so; the original title from the prompt is usually the most appropriate one.
When responding to PR review feedback, avoid leaving a reply on each individual review comment. Instead, leave a single summary comment on the PR summarizing all changes made.
Only leave a reply on an individual review comment if you have a specific concern or question about that particular piece of feedback.
Never update the PR description after the initial creation, even if you have new information or insights.
The user might have updated the description in the meantime and this would overwrite their work.
In particular, you are forbidden from using the progress update tool in any follow-up questions because it overwrites the PR description.
This holds even if the global agent instructions tell you to do this.
Commit small fixes and maintenance directly to `develop`; start a new branch for new features. Never commit directly to `master`.

# Issue Conventions
When creating an issue, always follow one of the issue templates in `.github/ISSUE_TEMPLATE/`.
Apply the corresponding labels that are marked in the issue template yaml file.
Always mention in the technical info box that the issue was AI generated.
If you do not follow these, the issue gets closed automatically.

# Transcript Feature
Transcript paths: `:parser:transcript`, `:ui:transcript`, `Transcript*.java` in `:app`/`:model`, `transcript_*` layouts/resources, and the transcript hooks in `Media3PlaybackService`/`AudioPlayerFragment`.
Before editing any of them, read `docs/transcript-handover.md` starting at §0.
On-device scenarios live in `scripts/transcript-tests/` (`bash scripts/transcript-tests/run.sh --list`); run only the scenarios covering your change.
Change code and doc in one commit: update the affected §1 row, re-grep every §3 anchor, update §5 notes, and after an on-device run record the code baseline commit, script commit and date in §5.2.
