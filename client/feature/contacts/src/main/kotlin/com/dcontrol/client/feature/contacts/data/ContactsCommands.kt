package com.dcontrol.client.feature.contacts.data

import android.Manifest
import android.provider.ContactsContract
import com.dcontrol.client.core.telephony.ContactNames
import com.dcontrol.client.core.telephony.ProviderChanges
import com.dcontrol.client.core.telephony.RuntimePermissions
import com.dcontrol.client.core.telephony.require
import com.dcontrol.client.core.telephony.whenGranted
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.OnlineWatcher
import com.dcontrol.client.core.websocket.protocol.ContactPayload
import com.dcontrol.client.core.websocket.protocol.ContactRef
import com.dcontrol.client.core.websocket.protocol.ContactsAccountsResult
import com.dcontrol.client.core.websocket.protocol.ContactsCreateRequest
import com.dcontrol.client.core.websocket.protocol.ContactsDeleteRequest
import com.dcontrol.client.core.websocket.protocol.ContactsDeleteResult
import com.dcontrol.client.core.websocket.protocol.ContactsListRequest
import com.dcontrol.client.core.websocket.protocol.ContactsListResult
import com.dcontrol.client.core.websocket.protocol.ContactsPhotoRequest
import com.dcontrol.client.core.websocket.protocol.ContactsUpdateRequest
import com.dcontrol.client.core.websocket.protocol.EmptyPayload
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.Permissions
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.core.websocket.typedCommand
import javax.inject.Inject
import javax.inject.Singleton

/** Handlers for `contacts.*`. Reads need READ_CONTACTS, writes WRITE_CONTACTS (same permission group). */
@Singleton
class ContactsCommands @Inject constructor(
    codec: ProtocolCodec,
    private val store: ContactsStore,
    private val photos: ContactPhotoUploader,
    private val permissions: RuntimePermissions,
) {
    val list: CommandHandler = typedCommand(codec, ContactsListRequest.serializer(), ContactsListResult.serializer()) { request ->
        requireRead()
        store.list(request.query, request.cursor)
    }

    val get: CommandHandler = typedCommand(codec, ContactRef.serializer(), ContactPayload.serializer()) { request ->
        requireRead()
        store.get(request)
    }

    val accounts: CommandHandler = typedCommand(codec, EmptyPayload.serializer(), ContactsAccountsResult.serializer()) {
        requireRead()
        ContactsAccountsResult(store.accounts())
    }

    val create: CommandHandler = typedCommand(codec, ContactsCreateRequest.serializer(), ContactPayload.serializer()) { request ->
        requireWrite()
        store.create(request.account, request.fields)
    }

    val update: CommandHandler = typedCommand(codec, ContactsUpdateRequest.serializer(), ContactPayload.serializer()) { request ->
        requireWrite()
        store.update(ContactRef(request.id, request.lookupKey), request.version, request.fields)
    }

    val delete: CommandHandler = typedCommand(codec, ContactsDeleteRequest.serializer(), ContactsDeleteResult.serializer()) { request ->
        requireWrite()
        store.delete(request.items)
    }

    val photo: CommandHandler = typedCommand(codec, ContactsPhotoRequest.serializer(), EmptyPayload.serializer()) { request ->
        requireRead()
        photos.upload(request)
        EmptyPayload()
    }

    private fun requireRead() = permissions.require(Permissions.CONTACTS, Manifest.permission.READ_CONTACTS)

    private fun requireWrite() =
        permissions.require(Permissions.CONTACTS, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
}

/** Sends `contacts.changed` while online (debounced), once READ_CONTACTS is granted. */
class ContactsWatcher @Inject constructor(
    private val changes: ProviderChanges,
    private val events: DeviceEventSender,
    private val permissions: RuntimePermissions,
    private val names: ContactNames,
) : OnlineWatcher {
    override suspend fun watch() = permissions.whenGranted(Manifest.permission.READ_CONTACTS) {
        changes.changes(ContactsContract.Contacts.CONTENT_URI).collect {
            names.clear()
            events.send(MessageTypes.CONTACTS_CHANGED, EmptyPayload.serializer(), EmptyPayload())
        }
    }
}
