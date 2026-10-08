package io.github.mvolkert.entryrecorder.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A state-toggled icon that morphs instead of hard-swapping, the Material 3 Expressive treatment for
 * a binary control (play/pause, mute, visibility). Pass the vector for the current state; when it
 * changes the outgoing icon scales away while the incoming one springs in, both on the theme's
 * spatial spring so the motion matches every other animation in the app.
 */
@Composable
fun MorphingIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    // AnimatedContent's transitionSpec is not a composable scope, so resolve the spring once here.
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    AnimatedContent(
        targetState = imageVector,
        transitionSpec = {
            val enter = fadeIn(spatial) + scaleIn(spatial, initialScale = 0.5f)
            val exit = fadeOut(spatial) + scaleOut(spatial, targetScale = 0.5f)
            enter togetherWith exit
        },
        label = "morphingIcon",
    ) { vector ->
        Icon(
            imageVector = vector,
            contentDescription = contentDescription,
            modifier = modifier,
            tint = tint,
        )
    }
}
