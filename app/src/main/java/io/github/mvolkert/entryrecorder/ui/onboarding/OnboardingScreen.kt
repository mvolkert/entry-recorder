package io.github.mvolkert.entryrecorder.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Doorbell
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mvolkert.entryrecorder.R
import io.github.mvolkert.entryrecorder.ui.adaptive.LocalWindowInfo
import io.github.mvolkert.entryrecorder.ui.adaptive.appContentMaxWidth
import io.github.mvolkert.entryrecorder.ui.adaptive.rememberWindowInfo
import io.github.mvolkert.entryrecorder.ui.components.ExpressiveIconBadge
import io.github.mvolkert.entryrecorder.ui.theme.Spacing
import io.github.mvolkert.entryrecorder.ui.theme.rememberExpressiveMotionEnabled
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The first-run flow: one page per step in a [HorizontalPager], with the action slot and the pager's own
 * Previous | Next row pinned to the window beneath it rather than carried by each page. Every page's text
 * block is centered on both axes and its title is a real heading for TalkBack; the
 * [OnboardingPermissionBar] and [OnboardingNavRow] below never move.
 *
 * Deliberately no snack bar — this composes outside `AppRoot`, so there is no [androidx.compose.material3.SnackbarHost]
 * here for [io.github.mvolkert.entryrecorder.ui.LocalAppSnackbar] to reach. Anything that goes wrong is said
 * inline on the page it happened to, which is also where the user is when they need to read it.
 */
@Composable
fun OnboardingScreen(
    onFinished: (landOnDevices: Boolean) -> Unit,
    onPermissionsSettled: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val animatePages = rememberExpressiveMotionEnabled()
    // Provided here rather than inherited: this screen never enters AppRoot's tree, and the width cap that
    // keeps a foldable's intro from stretching edge to edge reads this value.
    val windowInfo = rememberWindowInfo()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Re-reads the platform instead of taking this Boolean at face value, and refreshes the monitor the
        // way the launch-time request used to: the mic and the notification channel are both its inputs.
        viewModel.onResume()
        onPermissionsSettled()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The same on both ways back, because after two denials the settings hop is the *only* way left to
        // grant anything — and the battery exemption is only ever granted there. A monitor left running with
        // the permissions the user just changed is the bug this flow exists to avoid.
        viewModel.onResume()
        onPermissionsSettled()
    }

    // Declared before the event collector: a page change it is asked for has to move this very pager.
    val pagerState = rememberPagerState(pageCount = { state.pages.size })

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is OnboardingEvent.RequestPermission ->
                    permissionLauncher.launch(viewModel.platformName(event.permission))

                OnboardingEvent.OpenAppSettings -> settingsLauncher.launchOrReport(
                    appDetailsIntent(context.packageName),
                    viewModel::onSettingsScreenUnavailable,
                )

                OnboardingEvent.OpenBatterySettings -> settingsLauncher.launchOrReport(
                    batteryOptimizationSettingsIntent(),
                    viewModel::onSettingsScreenUnavailable,
                )

                is OnboardingEvent.GoToPage -> pagerState.goToPage(event.index, animatePages)

                is OnboardingEvent.Finished -> onFinished(event.landOnDevices)
            }
        }
    }
    // Covers returning from either settings screen and from the system sheet, which is the only moment a
    // permission can have changed behind the flow's back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    BackHandler { viewModel.onBackPressed() }

    // One direction only: what the user settled on is reported up, and the flow's own page changes arrive
    // as [OnboardingEvent.GoToPage] intents. Reading [PagerState.currentPage] here instead would report a
    // drag that merely passed the halfway mark, swapping both labels for a page nobody landed on, and a
    // state field driving the pager as well would give the page index two owners — a fast fling then pulls
    // the user back to the page the ViewModel had not finished hearing about.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { viewModel.onPageChanged(it) }
    }

    CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            // The page block and the pinned bar each carry the inset they border, so the content slot stays
            // inset-free rather than pushing the bar up by the navigation bar a second time.
            contentWindowInsets = WindowInsets(0),
        ) { paddingValues ->
            // The bar and the nav row are siblings the pager stops above rather than overlays it scrolls
            // under: the filled button's height never changes between pages, so this keeps its y-position
            // fixed *and* keeps a page that grew at 200 % font scale from sliding its last lines behind it.
            // Centered horizontally so both capped slots line up with the capped page block on a foldable.
            Column(
                Modifier.fillMaxSize().padding(paddingValues),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) { page ->
                    OnboardingPage(
                        step = state.pages[page],
                        pageIndex = page,
                        totalSteps = state.pages.size,
                        noticeRes = state.noticeRes.takeIf { page == state.currentPage },
                    )
                }
                OnboardingPermissionBar(
                    primary = state.primaryAction,
                    secondary = state.secondaryAction,
                    onPrimaryClick = viewModel::onPrimaryAction,
                    onSecondaryClick = viewModel::onSecondaryAction,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                // The positional way through the pages, under the action slots and above the system bar.
                // Nothing about a grant state reaches it, so a declined permission cannot stop the tour.
                OnboardingNavRow(
                    previous = state.previousAction,
                    next = state.nextAction,
                    onPreviousClick = viewModel::onPreviousAction,
                    onNextClick = viewModel::onNextAction,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
        }
    }
}

/**
 * One page's text block. Centered horizontally by the column and vertically by the arrangement's
 * [Alignment.CenterVertically], inside a scroll container so 200 % font scale grows the block instead of
 * clipping the pinned action away. Each [Text] fills the width and centers its own lines, because aligning
 * the column does not center a title that wraps to two lines.
 *
 * The block carries the same [appContentMaxWidth] cap as the bar under it — one measure for both halves of
 * the screen on a foldable or tablet, instead of a full-bleed paragraph over a capped button.
 */
@Composable
private fun OnboardingPage(
    step: OnboardingStep,
    pageIndex: Int,
    totalSteps: Int,
    noticeRes: Int?,
) {
    val copy = OnboardingContract.copyFor(step)
    val scheme = MaterialTheme.colorScheme
    // BoxScope, not the page slot, does the centering: a capped block placed by the pager would sit at the
    // leading edge of a foldable's page. The column fills the height, so only the horizontal axis is decided.
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .appContentMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            // CenterVertically is what seats a short page in the middle of the window rather than at its top,
            // while the same column still scrolls when a page outgrows the window.
            verticalArrangement = Arrangement.spacedBy(Spacing.lg, Alignment.CenterVertically),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_counter, pageIndex + 1, totalSteps),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            PageVisual(step)
            Text(
                text = stringResource(copy.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { heading() },
            )
            Text(
                text = stringResource(copy.bodyRes),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (noticeRes != null) {
                Text(
                    text = stringResource(noticeRes),
                    style = MaterialTheme.typography.bodyMedium,
                    // The error role, because this text exists only to explain why the button just tapped did
                    // nothing; anything milder would read as another line of the page's description.
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The leading glyph. Only the landing and completion pages get the expressive container — Design.md §3 puts
 * those at the playful tier; the two asks and the battery page sit at the low end of it, so they carry a
 * plain tinted icon with no shape container and no hero fill.
 *
 * [PageGlyphSize] is a raw dp on purpose, like every other glyph metric in the app: it is the size of a
 * drawn icon, not a gap between two components, so it does not belong to the [Spacing] scale.
 */
@Composable
private fun PageVisual(step: OnboardingStep) {
    val scheme = MaterialTheme.colorScheme
    when (step) {
        OnboardingStep.Welcome -> ExpressiveIconBadge(
            icon = Icons.Default.Doorbell,
            contentDescription = null,
            containerColor = scheme.primaryContainer,
            contentColor = scheme.onPrimaryContainer,
        )

        OnboardingStep.Finish -> ExpressiveIconBadge(
            icon = Icons.Default.CheckCircle,
            contentDescription = null,
            containerColor = scheme.tertiaryContainer,
            contentColor = scheme.onTertiaryContainer,
        )

        else -> Icon(
            imageVector = when (step) {
                is OnboardingStep.Ask -> when (step.permission) {
                    OnboardingPermission.Notifications -> Icons.Default.Notifications
                    OnboardingPermission.Microphone -> Icons.Default.Mic
                }

                else -> Icons.Default.BatterySaver
            },
            contentDescription = null,
            modifier = Modifier.size(PageGlyphSize),
            tint = scheme.primary,
        )
    }
}

private val PageGlyphSize: Dp = 48.dp

/**
 * Moves the pager to [index] on the flow's own initiative. An already-settled request is dropped rather
 * than re-run, so a repeat tap cannot restart an animation the user is watching.
 */
private suspend fun PagerState.goToPage(index: Int, animate: Boolean) {
    if (currentPage == index && !isScrollInProgress) return
    if (animate) animateScrollToPage(index) else scrollToPage(index)
}

/** The app's own page in phone settings: where a permission the system no longer asks about is fixed. */
private fun appDetailsIntent(packageName: String): Intent = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    "package:$packageName".toUri(),
)

/**
 * The system's battery-optimization list. Deliberately this screen rather than
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which would need a manifest permission the project has
 * decided not to take: the user opts out of optimization for the app by name in this list instead.
 */
private fun batteryOptimizationSettingsIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

/** Some skins ship no battery-optimization screen; a dead tap has to be said out loud, not swallowed. */
private fun ActivityResultLauncher<Intent>.launchOrReport(intent: Intent, onUnavailable: () -> Unit) {
    try {
        launch(intent)
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
    }
}
