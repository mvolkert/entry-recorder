package io.github.mvolkert.entryrecorder.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Formats a string resource for code that is not a composable.
 *
 * A ViewModel that calls `context.getString` cannot be constructed off-device, so nothing about its
 * behaviour could be checked without a phone attached. Asking for [UiStrings] instead keeps the resource
 * *id* the thing that travels, the class under test holds no Context for the sake of one message, and a
 * test can substitute a fake that answers `"res-42"` for id 42.
 *
 * Presentation code — a composable, an Activity, a notification builder — should keep using
 * `stringResource` / `resources.getString` directly: those are the layer that owns the wording, and they
 * already have a live Context.
 */
interface UiStrings {
    operator fun invoke(@StringRes id: Int, vararg args: Any): String

    /** Plurals form of [invoke]: [id] is a plurals resource and [quantity] picks which of its forms is used. */
    fun quantity(@PluralsRes id: Int, quantity: Int, vararg args: Any): String
}

/** The device-backed implementation, delegating to the application context's resources. */
class AndroidUiStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) : UiStrings {
    override fun invoke(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    override fun quantity(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, quantity, *args)
}
