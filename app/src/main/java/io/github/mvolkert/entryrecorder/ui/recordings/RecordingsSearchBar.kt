package io.github.mvolkert.entryrecorder.ui.recordings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mvolkert.entryrecorder.R

/**
 * The Recordings search band, in the two halves the pinned material3 asks for.
 *
 * `material3:1.5.0-beta01` treats the state-based [SearchBar] as a *trigger*: its own surface wraps the
 * input field in `DisableSoftKeyboard` (an `InterceptPlatformTextInput` that never resolves), so the
 * collapsed field can be read and tapped but cannot be typed into. Keyboard, cursor and editing all
 * live in [ExpandedFullScreenSearchBar], which is the component the spec recommends on a compact
 * screen. The two are therefore one feature and are kept together: [RecordingsSearchTrigger] sits in
 * the top band and [RecordingsSearchSurface] draws the sheet over the screen, with the caller's
 * [content] as its results.
 *
 * Both halves are stable, non-experimental APIs in this artifact, so no expressive opt-in is needed
 * here and Design.md §7's "screens never call a raw expressive API" does not apply to them.
 */
@Composable
internal fun RecordingsSearchTrigger(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    query: String,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBar(
        state = searchBarState,
        inputField = {
            RecordingsSearchInput(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                query = query,
                onClearQuery = onClearQuery,
                onSearch = onSearch,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier,
    )
}

/**
 * The expanded half: a full-screen sheet that opens from the trigger's bounds and owns the keyboard.
 * Compose it unconditionally — [SearchBarState] gates it internally, so it costs nothing while
 * collapsed, and the sheet's own back and predictive-back handling stays intact.
 */
@Composable
internal fun RecordingsSearchSurface(
    searchBarState: SearchBarState,
    textFieldState: TextFieldState,
    query: String,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = {
            RecordingsSearchInput(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                query = query,
                onClearQuery = onClearQuery,
                onSearch = onSearch,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        modifier = modifier,
        content = content,
    )
}

/**
 * The one field both halves show, so the query never has two editors. Filtering is live from the
 * ViewModel, so the IME action has nothing to commit: [onSearch] only puts the keyboard away and
 * leaves the typed text and the results on screen.
 */
@Composable
private fun RecordingsSearchInput(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    query: String,
    onClearQuery: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchBarDefaults.InputField(
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        onSearch = { onSearch() },
        modifier = modifier,
        placeholder = { Text(stringResource(R.string.recordings_search_placeholder)) },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.recordings_cd_search))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClearQuery) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recordings_cd_clear))
                }
            }
        },
    )
}
