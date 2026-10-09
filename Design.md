---
name: Android Material 3 Expressive Design System
target: playful        # balanced | playful
---

# DESIGN.md

Use the default Material 3 Expressive APIs. They carry the design.

## 1. Rules

1. Never type a hex color, spring, corner radius, font size or component size. Use the API that provides it.
2. Read color, type, shape and motion from `MaterialTheme.colorScheme`, `.typography`, `.shapes` and `.motionScheme`. Prefer the default component and its `*Defaults`.
3. Do not invent API names. If something does not resolve or compile, read the API reference for the pinned version.
4. Stay inside the level budget (section 3). Accessibility wins over level (section 6).
5. Anything beyond defaults is an exception (section 7).

## 2. Versions and theme

- Pin an exact `androidx.compose.material3:material3 = "1.5.0-beta01"` or newer alpha. No `+` ranges. Stable 1.4.0 cannot use the expressive APIs: if one is unresolved or reported internal, you are on 1.4.0. If no alpha is acceptable, ship standard Material 3 and do not hand-build expressive.
- Most expressive APIs need no opt-in. `LoadingIndicator`, `ContainedLoadingIndicator` and `MaterialShapes` may need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`. Keep opt-ins in the design-system module.
- Wear OS and XR use their own libraries: `androidx.wear.compose:compose-material3`, `androidx.xr.compose.material3`.

**Do not write the old names**

| Old | Use |
|---|---|
| `SplitButtonLayout` | `SplitButton` |
| `TonalToggleButton` | `FilledTonalToggleButton` |
| `ToggleButtonDefaults.shapes()` | `shapesFor(...)` |
| `LocalMotionScheme` | `MaterialTheme.motionScheme` |
| Stateless `Slider` / `RangeSlider` | `SliderState`-based `Slider(state = ...)` |
| `activeRangeStart` / `activeRangeEnd` | `startValue` / `endValue` |
| `rememberModalBottomSheetState` | `rememberBottomSheetState` |
| `RichTimePickerDialog` | `VibrantTimePickerDialog` |
| Old `SearchBar` with `onExpandedChange` | `SearchBarState`-based `SearchBar` |
| `TextFieldLabelPosition.Attached` | `Inside` or `Cutout` |

**Theme.** Wrap the app once. No screen creates its own theme.

```kotlin
@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> BrandDarkColors    // Material Theme Builder export, vivid seed color
        else -> BrandLightColors
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,              // shapes and typography omitted: Material defaults
    )
}
```

Playful needs reliably vivid color, and wallpaper-derived dynamic color can come out muted. So the vivid brand scheme is the main source and dynamic color is a user setting.

## 3. Levels

The project target is in the front matter. A screen uses its own level and **never goes above the target**. When unsure, use Balanced.

| Lever | Balanced | Playful |
|---|---|---|
| Motion | Built-in component morphs, list changes, show/hide | Adds shared-element transitions, wavy progress, `LoadingIndicator`, one celebration moment per completed flow |
| Color | Container roles, one `tertiary` accent area, filled selection | Adds large `primaryContainer` / `tertiaryContainer` blocks, vibrant variants (`MenuDefaults.itemVibrantColors()`, `VibrantTimePickerDialog`), `TextFieldDefaults.tonalColors()` |
| Shape | Cards `shapes.extraLarge`, hero `shapes.extraLargeIncreased`, components morph through their `shapes` | Adds `MaterialShapes` on avatars, hero media and icon containers, `shapes.extraExtraLarge` image masks, morph on selection |
| Type | Emphasized styles on section headers and selected labels | Adds `displayMediumEmphasized` / `displaySmallEmphasized` for hero numbers |
| Size and containment | Primary action one size up, filled cards, connected button groups | Adds large or extra-large primary action on hero screens, carousels, `FloatingActionButtonMenu`, floating toolbars, `SplitButton` |
| Expressive moments per screen | At most 2 | At most 3 |

An **expressive moment** is a hero card, a decorative shape family, a shared-element transition, a celebration animation, or an extra-large control.

**Playful screens:** home, landing, onboarding, empty states, success and completion, media browse, profile. **Balanced screens:** everything else. On settings, dense data, payments, legal, permissions and errors use the low end of Balanced: no hero, no decorative shapes, no celebration.

**Rules**
- Repeated items (list rows, grid cells) never carry expressive moments. The container does.
- One decorative shape family app-wide.
- With animations off: flat progress, crossfades, no celebration.

## 4. Foundations

**Color.** `primary` is the one main action. `primaryContainer`, `secondaryContainer` and `tertiaryContainer` are large soft fills and selection. `surfaceContainerLowest` to `surfaceContainerHighest` are layers (High for cards, Highest for inputs and segmented items). `onSurface` and `onSurfaceVariant` are text and icons. `outline` and `outlineVariant` are borders and dividers. Always pair a fill with its `on` role. Components already apply state layers and elevation tone, so do not recreate them.

**Type.** Use the 15 styles (`display`, `headline`, `title`, `body`, `label`, each Large, Medium, Small) and their `...Emphasized` twins. Emphasized is for short, high-impact text, never paragraphs. Never set `fontSize`, `fontWeight`, `lineHeight` or `letterSpacing` on a `Text`. Headings carry `Modifier.semantics { heading() }`.

**Shape.** Tokens: `extraSmall`, `small`, `medium`, `large`, `largeIncreased`, `extraLarge`, `extraLargeIncreased`, `extraExtraLarge`. Buttons, icon buttons, toggle buttons and filter chips morph through their own `shapes`, so prefer that to hand-rolled morphing. `MaterialShapes` has 35 presets. Clip with `.toShape()` and morph with `Morph(a, b).toShape(progress)`. If you animate a corner by hand, clamp it to zero or more.

**Motion.** Read specs from `MaterialTheme.motionScheme`. Never call `spring(...)` or `tween(...)`.

| Accessor | For |
|---|---|
| `fastSpatialSpec()`, `defaultSpatialSpec()`, `slowSpatialSpec()` | Position, size, shape, scale, rotation (small, medium, large objects). May overshoot |
| `fastEffectsSpec()`, `defaultEffectsSpec()`, `slowEffectsSpec()` | Color, alpha. Never overshoots |

Spatial for geometry, effects for appearance. Prefer a component's built-in animation to custom animation. List changes: `Modifier.animateItem(fadeInSpec = motion.defaultEffectsSpec(), placementSpec = motion.defaultSpatialSpec(), fadeOutSpec = motion.defaultEffectsSpec())`. Pair motion with haptics where Compose offers them (`ToggleOn`, `ToggleOff`, `SegmentFrequentTick`, `Confirm`, `Reject`).

**Layout.** Component padding comes from the component. Where you must space things, use multiples of 8dp. Screen margin is 16dp on compact widths and 24dp on medium and up. Be edge-to-edge (`enableEdgeToEdge()`, `WindowInsets`). Branch on `currentWindowAdaptiveInfo()` size classes, never on device type. Use `NavigationSuiteScaffold`, `ListDetailPaneScaffold` and `SupportingPaneScaffold` instead of hand-rolled adaptive layouts.

## 5. Components

**Cards** are recipes built from defaults.

| Recipe | Build | Level |
|---|---|---|
| Standard | `Card(onClick)`, `surfaceContainerHigh`, `shapes.extraLarge` | Both |
| Dense | `OutlinedCard` | Both |
| Hero | `Card(onClick)`, `tertiaryContainer` or `primaryContainer`, `shapes.extraLargeIncreased`, emphasized title | One per screen |
| Browse | `HorizontalMultiBrowseCarousel`, `HorizontalUncontainedCarousel` or `HorizontalCenteredHeroCarousel`, items clipped with `Modifier.maskClip(MaterialTheme.shapes.extraLarge)` | Playful |
| Uniform list rows | `SegmentedListItem`, not many cards | Both |

The whole card is one click target. Never nest cards or give every card the hero color. No custom press animation on cards.

**Icons.** Material Symbols, one style app-wide (Rounded suits expressive), as vector drawables. Filled for selected, outlined for unselected. Do not set icon sizes inside Material components. Use `IconButton` variants, which give the 48dp target, and pass the component's default `shapes` on toggles so they morph. Playful: put an icon in a `MaterialShapes` container with `primaryContainer` and `onPrimaryContainer`. Meaningful icons need a `contentDescription`, decorative ones `null`. The adaptive app icon needs a monochrome layer.

**Sliders.** Use the default `Slider` and `RangeSlider`. Do not emulate sizes with a custom thumb or track. The call shape changed in alpha28 (stateless overloads hidden, `SliderState`) and alpha29 (`onValueChange` required, new parameter order), so copy it from the pinned version's reference. Add a haptic tick per step for discrete sliders, set `stateDescription`, show the value when precision matters, and keep the label outside the slider. Never reuse a slider as a progress display.

**Component matrix**

| Need | Use |
|---|---|
| Primary action | `Button` |
| Choice among 2 to 5 | Connected button group of `ToggleButton` |
| Action plus menu | `SplitButton` |
| Main screen action | `FloatingActionButton`. Several actions: `FloatingActionButtonMenu` |
| Floating actions | `HorizontalFloatingToolbar`, `VerticalFloatingToolbar` |
| Bars | `TopAppBar`, `MediumFlexibleTopAppBar`, `LargeFlexibleTopAppBar`, `BottomAppBar`, `FlexibleBottomAppBar` |
| Navigation | `NavigationSuiteScaffold` (`ShortNavigationBar`, `WideNavigationRail`) |
| Menus | `DropdownMenuPopup`, `SelectableDropdownMenuItem`, `CheckableDropdownMenuItem` |
| Search | `SearchBar` with `SearchBarState` |
| Chips | `FilterChip`, `InputChip` |
| Short wait | `LoadingIndicator` |
| Long task | `LinearWavyProgressIndicator`, `CircularWavyProgressIndicator`. Flat indicators in dense lists |
| Sheets | `rememberBottomSheetState`, `ModalBottomSheet` |

## 6. Accessibility

- Contrast 4.5:1 for text and 3:1 for icons and boundaries, in light, dark and high contrast. Vivid color must still pass.
- Touch targets 48dp or larger. Layouts survive 200% font scale.
- Every interactive element has a role and name. Stateful ones have a `stateDescription`. Merge semantics on cards and rows.
- Keyboard and D-pad reach everything, with visible focus.
- Honor the system animation setting. Never carry information in motion alone. Color is never the only signal.

## 7. Exceptions

Anything beyond defaults needs a written reason, one location in the design-system module, and a re-check on every Material upgrade. Allowed: the brand color scheme, fixed brand colors harmonized with `Blend.harmonize`, a brand font via the `Typography` default-font-family constructor, and layout spacing. Everything else stays default.

Wrap components (`AppButton`, `AppCard`, `AppSlider`) in a design-system module. Wrappers pass Material defaults through and add no values.

**Recorded exceptions in this app.** Each lives in exactly one place, each carries a why-comment at that
place, and each gets re-checked on every Material upgrade.

- `ui/live/LiveCamerasScreen.kt` — the REC capsule sets `fontSize = 9.sp` with `lineHeight = 12.sp`. Dot
  plus label has to stay shorter than the device-name line, otherwise the header row and the video box
  reflow every time a recording starts or stops. No type role is that small.
- `ui/components/CelebrationCheck.kt` — one hold constant for the celebration (1.5 s). Its enter and exit
  are `fastSpatialSpec()` / `fastEffectsSpec()`; Material says nothing about how long a finished flow
  should stay on screen before the check dismisses itself.
- `ui/theme/Theme.kt` — `VideoScrim` and `onScrimColor`. Video pillarboxes an arbitrary camera frame, so
  the fill behind it and the glyphs on top of it cannot track the scheme. The pair is handed down once
  through `LocalContentColor` instead of being retyped per surface.
- `ui/theme/Theme.kt` — the fixed status palette (`StatusColor`, `eventTypeColor`, `recordingStatusColor`,
  `recordingGlyphColorFor`, `monitorStatusColor`, `statusWarnColor`). Trigger and recording hues are semantics,
  not theming, so they stay independent of the accent preset and of dynamic color. The record glyph is the one
  two-step hue (`recordingGlyphColorFor`): a FAB glyph is a non-text element, so it needs only 3:1, and the one
  REC red clears that on the resting FAB's light neutral (`surfaceBright`, ~4.7:1) but not on the dark one —
  the dark step is the same hue lightened. `StatusPaletteContrastTest` re-proves every container/on pairing
  and both glyph steps on each test run.
- `ui/theme/Theme.kt` — `Spacing` steps. Material 3 ships no spacing tokens, so the multiples of 4/8 that
  layouts already used are named once instead of repeating as dp literals.
- Shape gap in the pinned material3 — `MaterialTheme.shapes` has no `full` token, so `CircleShape` renders
  every stadium pill (`StatusChip`, `CelebrationCheck`, the search field). Swap to the token when it lands.
- `ui/components` holds the wrappers section 7 asks for: `StatusChip`, `RecordFab`, `LoadingSpot`,
  `ProgressLine`, `ExpressiveIconBadge`, `CelebrationCheck`, `MorphingIcon`. They own the expressive
  opt-ins, the single `MaterialShapes.Cookie9Sided` decorative family and the animations-off gate
  (`rememberExpressiveMotionEnabled()`); screens call these and never a raw expressive API.

## 8. Checklist and rollout

**Do not**
- write `Color(0x...)`, `spring(`, `tween(`, `RoundedCornerShape(`, `fontSize =` or hard-coded icon and button sizes in a component
- use expressive APIs on stable 1.4.0, or hand-build them
- use a spatial spec for color or an effects spec for position
- exceed a screen's moment budget or use a level above the target
- put emphasized type in paragraphs, or nest cards
- invent API names

**Done when**
1. No custom values (search for the patterns above) and no old names from the table in section 2.
2. Correct at 360dp, 840dp and 1280dp widths, in dark theme, at 200% text and in high contrast.
3. Animation uses motion-scheme specs.
4. The screen is within its level's budget.

**Rollout for an existing M3 app**
1. Pin the alpha, switch to `MaterialExpressiveTheme`, fix compile errors with the rename table.
2. Reach Balanced everywhere: expressive components, emphasized type, shape tokens, connected button groups.
3. Add the vivid color source and check contrast on every screen.
4. Raise the Playful screens one at a time, within budget. Re-run the checklist after each step.
5. For View-based screens, bring in expressive through `ComposeView`.
