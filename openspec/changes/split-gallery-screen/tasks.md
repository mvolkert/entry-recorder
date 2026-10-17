# Tasks

`recordings-archive` — *Searchable, filterable gallery*, *Merged local and server recordings*, *Range
multi-select* — and `export` behaviour must stay exactly as shipped; this change moves UI code only.

## 1. Share launcher helper

- [ ] 1.1 Extract `rememberShareLauncher()` (gateway `EntryPointAccessors` resolution + `context` + snackbar hop)
      returning the share dispatch lambda, and call it from the existing `LaunchedEffect(Unit)` event collector,
      which does not move; verify: `.\gradlew.bat compileDebugKotlin` passes and the collector's `when` arms keep
      the same order and the same awaited `showMessage` calls.

## 2. Search band extraction

- [ ] 2.1 Extract `rememberRecordingsSearchBand(viewModel, selectionMode, onClearFilters)` returning
      `searchBarState` / `textFieldState` / `clearSearchQuery` / `clearAllFilters`, with the `snapshotFlow`
      one-way bridge and the selection-mode collapse effect inside it, plus the KDoc warning that it must be
      composed unconditionally; verify: `compileDebugKotlin` passes and the root calls it once, not behind an `if`.

## 3. Row callback and overlay slim-down

- [ ] 3.1 Add the internal `GalleryItem.exportAs(kind, viewModel)` extension collapsing the SHARE / GALLERY /
      FOLDER branches and use it in the `rows` lambda; leave `onExportRawFolder`'s local-only branch alone; verify:
      `compileDebugKotlin` passes and a server row's card menu still hides the raw-folder entry.
- [ ] 3.2 Extract `@Composable private fun BoxScope.GalleryRunOverlay(…)` holding the `RunDisplay` mapping,
      `RunProgressStrip`, and the celebration check with identical `align`/`padding`; verify: `compileDebugKotlin`
      and `.\gradlew.bat lintDebug` pass, and the root composable is under ~180 lines.

## 4. Verification

- [ ] 4.1 Run `.\gradlew.bat lintDebug` and `ktlintCheck`/`detekt` if configured; fix only what this change
      introduced.
- [ ] 4.2 On a device, walk the gallery end to end: open search, type with a device filter active (no eaten
      keystroke), clear all filters from the empty state, long-press a row for a range, share one clip (system
      sheet appears with the right labels), batch-export two clips to the folder (progress strip shows "k of n",
      celebration check appears, list does not shift), delete one local clip, and confirm back gesture exits
      multi-select rather than the tab.
