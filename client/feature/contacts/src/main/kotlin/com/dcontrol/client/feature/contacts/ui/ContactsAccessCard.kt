package com.dcontrol.client.feature.contacts.ui

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dcontrol.client.core.designsystem.component.SectionCard
import com.dcontrol.client.core.permissions.rememberRuntimePermissionsState
import com.dcontrol.client.core.telephony.PermissionSets
import com.dcontrol.client.feature.contacts.R

object ContactsAccessTags {
    const val CARD = "contacts_access_card"
    const val GRANT = "contacts_access_grant"
}

/** Status-screen card: Contacts permission (read + write) for the web Contacts tab. */
@Composable
fun ContactsAccessCard(modifier: Modifier = Modifier) {
    val state = rememberRuntimePermissionsState(PermissionSets.CONTACTS)
    ContactsAccessCardContent(granted = state.granted, blocked = state.blocked, onGrant = state::request, modifier = modifier)
}

@Composable
fun ContactsAccessCardContent(granted: Boolean, blocked: Boolean, onGrant: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier.testTag(ContactsAccessTags.CARD)) {
        Text(stringResource(R.string.contacts_access_title), style = MaterialTheme.typography.titleMedium)
        if (granted) {
            Text(stringResource(R.string.contacts_access_granted), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(stringResource(R.string.contacts_access_missing), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onGrant, modifier = Modifier.testTag(ContactsAccessTags.GRANT)) {
                Text(stringResource(if (blocked) R.string.contacts_access_open_settings else R.string.contacts_access_grant))
            }
        }
    }
}
