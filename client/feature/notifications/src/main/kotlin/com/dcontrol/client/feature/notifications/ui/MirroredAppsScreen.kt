package com.dcontrol.client.feature.notifications.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dcontrol.client.core.database.MirroredAppDao
import com.dcontrol.client.core.database.MirroredAppEntity
import com.dcontrol.client.feature.notifications.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data object MirroredAppsDestination

fun NavGraphBuilder.mirroredAppsScreen(onBack: () -> Unit) {
    composable<MirroredAppsDestination> { MirroredAppsRoute(onBack) }
}

object MirroredAppsTags {
    const val EMPTY = "mirrored_apps_empty"
    fun toggle(packageName: String) = "mirrored_app_$packageName"
}

@HiltViewModel
class MirroredAppsViewModel @Inject constructor(private val dao: MirroredAppDao) : ViewModel() {

    val apps: StateFlow<List<MirroredAppEntity>> =
        dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { dao.setEnabled(packageName, enabled) }
    }
}

@Composable
fun MirroredAppsRoute(onBack: () -> Unit, viewModel: MirroredAppsViewModel = hiltViewModel()) {
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    MirroredAppsScreen(apps = apps, onToggle = viewModel::setEnabled, onBack = onBack)
}

/** Apps seen posting notifications so far; only enabled ones are mirrored to the web. */
@Composable
fun MirroredAppsScreen(
    apps: List<MirroredAppEntity>,
    onToggle: (packageName: String, enabled: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.mirrored_apps_back)) }
            Text(stringResource(R.string.mirrored_apps_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.mirrored_apps_text), style = MaterialTheme.typography.bodyMedium)
            if (apps.isEmpty()) {
                Text(
                    stringResource(R.string.mirrored_apps_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(MirroredAppsTags.EMPTY),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.appLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = app.enabled,
                            onCheckedChange = { onToggle(app.packageName, it) },
                            modifier = Modifier.testTag(MirroredAppsTags.toggle(app.packageName)),
                        )
                    }
                }
            }
        }
    }
}
