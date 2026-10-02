# Project Description
- A client App for IP Cameras, SIP Calling on triggers and recording triggers via camera or on device analyzation.

# Project
Modern Android app in Kotlin with Jetpack Compose.
- Read SDK levels, package names, modules, and libraries from the Gradle files. Do not assume them.
- Dependencies and plugin versions live in `gradle/libs.versions.toml` if it exists. Add new ones there.
- Existing project conventions override this file. Match the style of neighboring code.

# Modules
- Do not assume a fixed module list. Run `./gradlew projects` or read `settings.gradle.kts` when needed.
- Dependencies point one way: features depend on shared/core code, never the reverse, and feature modules do not depend on each other.
- Put new code in the module it belongs to.

# Commands
Run from the project root. On Windows use `gradlew.bat`. Unqualified tasks run on every module. Qualify with a module path (`:module:testDebugUnitTest`) to target one.
- Compile: `./gradlew compileDebugKotlin`
- Unit tests: `./gradlew testDebugUnitTest`
- Lint: `./gradlew lintDebug`
- APK: `./gradlew assembleDebug`
- Also run `ktlintCheck` or `detekt` if configured.

# Ask First
- Adding or upgrading dependencies, or bumping Gradle, Kotlin, AGP, or Compose.
- Changing manifest permissions, services, or exported components.

# Architecture
- Compose with Material 3. Animate with `MaterialTheme.motionScheme` tokens, no custom durations.
- Unidirectional Data Flow. ViewModels expose immutable `StateFlow` state. The UI sends intents through lambdas.
- One-shot events use `Channel<UiEvent>(Channel.BUFFERED)` exposed as `receiveAsFlow()`. Do not use `SharedFlow` for events that must be delivered.
- Use the DI framework already in the project (Hilt, Koin, etc.). Get ViewModels at screen level only.
- Coroutines only. Inject dispatchers, never hardcode `Dispatchers.*` in testable classes. IO for blocking I/O, Default for CPU work.
- Collect with `collectAsStateWithLifecycle()`.
- `remember` for transient UI state, `rememberSaveable` only for state that must survive recreation, `derivedStateOf` for derived values.
- Do not add `@Immutable` or `@Stable` by default. Use them only to fix a proven unstable-parameter recomposition problem.
- Sealed interfaces for UI state and events, with exhaustive `when`.

# Code Style
- Modern Kotlin: scope functions, extension functions, value classes, `when` expressions.
- No `!!`, no raw types, no Java-style boilerplate.
- KDoc only on public or non-obvious APIs.
- No comments describing what code does. A one-line why-comment is fine for non-obvious workarounds.
- User-facing strings go in `strings.xml`.

# Comments
- KDoc on public or non-obvious APIs.
- No inline comments by default; only for real workarounds (platform quirks, tricky logic).
- No changelog comments ("Fixed:", "Definite Fix:", "Updated to..."). That belongs in the commit message, not the file.
- Remove comments that don't fit that structure.

# Error Handling
- Errors are data in the UI: a sealed `UiEvent.Error` or part of UI state.
- Never swallow exceptions silently. Rethrow `CancellationException`.

# Testing
- Use the test libraries already in the project. Test ViewModels with injected test dispatchers and fakes rather than mocks where practical.

# Output
- Lead with the code or conclusion. No filler openers.
- Keep prose short, but always state assumptions, risks, and blocking questions.

# Workflow & Verification
- Read and search before editing. Prefer targeted edits over full rewrites.
- After any change, run `./gradlew compileDebugKotlin` and fix errors and warnings you introduced.
- If logic changed, run `testDebugUnitTest`. If UI changed, run `lintDebug`.
- For Compose, check lazy list keys, recomposition scope, and effect keys.
- If a command can't be run, say what wasn't verified.
- Then summarize the change in 1–2 sentences.