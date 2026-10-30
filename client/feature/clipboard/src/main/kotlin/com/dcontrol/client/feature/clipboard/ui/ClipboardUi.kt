package com.dcontrol.client.feature.clipboard.ui

import androidx.annotation.StringRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.feature.clipboard.R
import com.dcontrol.client.feature.clipboard.data.Clipboard
import com.dcontrol.client.feature.clipboard.data.ClipboardSendResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

object ClipboardTags {
    const val SEND = "clipboard_send"
    const val RESULT = "clipboard_result"
}

@HiltViewModel
class ClipboardViewModel @Inject constructor(private val clipboard: Clipboard) : ViewModel() {
    /** Called from a button tap, so the activity has focus and may read the clipboard. */
    fun send(): ClipboardSendResult = clipboard.sendCurrentClip()
}

/** Status-screen card with the in-app "Send clipboard" button. */
@Composable
fun ClipboardCard(modifier: Modifier = Modifier, viewModel: ClipboardViewModel = hiltViewModel()) {
    ClipboardCardContent(onSend = viewModel::send, modifier = modifier)
}

@Composable
fun ClipboardCardContent(onSend: () -> ClipboardSendResult, modifier: Modifier = Modifier) {
    var result by rememberSaveable { mutableStateOf<ClipboardSendResult?>(null) }
    SectionCard(modifier = modifier) {
        Text(stringResource(R.string.clipboard_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.clipboard_text), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { result = onSend() }, modifier = Modifier.testTag(ClipboardTags.SEND)) {
            Text(stringResource(R.string.clipboard_send))
        }
        result?.let {
            Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(ClipboardTags.RESULT))
        }
    }
}

@StringRes
internal fun ClipboardSendResult.messageRes(): Int = when (this) {
    ClipboardSendResult.Sent -> R.string.clipboard_sent
    ClipboardSendResult.Empty -> R.string.clipboard_empty
    ClipboardSendResult.Offline -> R.string.clipboard_offline
}
