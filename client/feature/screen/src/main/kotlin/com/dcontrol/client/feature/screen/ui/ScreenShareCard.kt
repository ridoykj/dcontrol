package com.dcontrol.client.feature.screen.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.feature.screen.R

object ScreenShareTags {
    const val CARD = "screen_share_card"
}

/** Status-screen card explaining per-session consent and the secure-content/mic caveats. */
@Composable
fun ScreenShareCard(modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier.testTag(ScreenShareTags.CARD)) {
        Text(stringResource(R.string.screen_share_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.screen_share_consent), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.screen_share_caveats),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
