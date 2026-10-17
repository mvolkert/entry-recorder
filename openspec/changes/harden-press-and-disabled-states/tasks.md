# Tasks

## 1. Make the row press visible

- [x] 1.1 Hoist `val shape = MaterialTheme.shapes.extraLarge`, move the row's fill onto the incoming chain as
  `clip(shape).background(containerColor)` ahead of `combinedClickable`, and give the `Card`
  `containerColor = Color.Transparent` with that shape; verify `:app:compileDebugKotlin` is clean and the
  animated selection colour still drives the fill

- [x] 1.2 Record the load-bearing order in a comment at the call site and verify by reading that no later
  modifier can slip between `background` and `combinedClickable`

- [ ] 1.3 On-device with a populated archive: hold a row and pixel-sample the fill while held, confirming it
  steps away from the resting container and back on release — blocked: the Verso at 192.168.1.100 is offline,
  and a manual recording on the AVD produced no file ("No recordings found.", "Total storage: 0 B")

## 2. One disabled-content recipe

- [x] 2.1 Add `DisabledContentAlpha = 0.38f` and `disabledContentColor()` (`onSurface` at that alpha) to
  `ui/theme/Theme.kt` and verify `:app:compileDebugKotlin`

- [x] 2.2 Point `monitorStatusColor(DISABLED)` and the Live `faded` trigger tint at it, deleting both
  `onSurfaceVariant.copy(alpha = 0.35f)` literals, each with a KDoc line naming what it tints; verify by
  grep that no hand-picked disabled alpha remains outside the slider tokens in `JpegFramePlayer.kt`

- [x] 2.3 On-device: toggle monitoring off and pixel-sample the status dot — measured `#9A959C` against the
  container `#E6E0E9`, matching `onSurface` at 38 % (predicted `#99959C`); the old rule would have read about
  `#B3AEB8`. Monitoring restored afterwards

## 3. Gates

- [x] 3.1 Run `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug` green

## Workflow follow-up

- Archive after 1.3 or with 1.3 recorded as the same hardware-blocked residual as
  `add-app-bar-scroll-containers` 4.4.
- Re-run a TalkBack pass over a row when an archive exists; `onClickLabel` / `onLongClickLabel` are
  unchanged, and `Role.Button` is added only if the gesture still reads unnamed.
