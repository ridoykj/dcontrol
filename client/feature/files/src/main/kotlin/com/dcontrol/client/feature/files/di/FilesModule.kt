package com.dcontrol.client.feature.files.di

import android.webkit.MimeTypeMap
import com.dcontrol.client.core.network.device.DeviceHttpClient
import com.dcontrol.client.core.storage.StorageAccess
import com.dcontrol.client.core.storage.StorageRoots
import com.dcontrol.client.core.websocket.CommandHandler
import com.dcontrol.client.core.websocket.DeviceEventSender
import com.dcontrol.client.core.websocket.EventHandler
import com.dcontrol.client.core.websocket.protocol.MessageTypes
import com.dcontrol.client.core.websocket.protocol.ProtocolCodec
import com.dcontrol.client.feature.files.data.FileSystem
import com.dcontrol.client.feature.files.data.FilesCommands
import com.dcontrol.client.feature.files.transfer.AndroidTransferNotifier
import com.dcontrol.client.feature.files.transfer.MediaScannerIndexer
import com.dcontrol.client.feature.files.transfer.RoomTransferStore
import com.dcontrol.client.feature.files.transfer.TransferApi
import com.dcontrol.client.feature.files.transfer.TransferEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object FilesModule {

    @Provides
    @Singleton
    fun fileSystem(roots: StorageRoots): FileSystem = FileSystem(
        roots = { roots.volumes().map { File(it.path) } },
        blocked = { roots.blockedPaths() },
        mimeOf = { name ->
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        },
    )

    @Provides
    @Singleton
    fun transferEngine(
        http: DeviceHttpClient,
        codec: ProtocolCodec,
        store: RoomTransferStore,
        fileSystem: FileSystem,
        events: DeviceEventSender,
        notifier: AndroidTransferNotifier,
        indexer: MediaScannerIndexer,
    ): TransferEngine = TransferEngine(
        api = TransferApi(http, codec.json),
        store = store,
        fileSystem = fileSystem,
        events = events,
        notifier = notifier,
        indexer = indexer,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ).also { it.start() }

    @Provides
    @Singleton
    fun filesCommands(
        codec: ProtocolCodec,
        fileSystem: FileSystem,
        roots: StorageRoots,
        access: StorageAccess,
        engine: TransferEngine,
    ) = FilesCommands(codec, fileSystem, roots, access, engine)

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FILES_ROOTS)
    fun roots(commands: FilesCommands): CommandHandler = commands.roots

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FILES_LIST)
    fun list(commands: FilesCommands): CommandHandler = commands.list

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FILES_MKDIR)
    fun mkdir(commands: FilesCommands): CommandHandler = commands.mkdir

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FILES_RENAME)
    fun rename(commands: FilesCommands): CommandHandler = commands.rename

    @Provides
    @IntoMap
    @StringKey(MessageTypes.FILES_DELETE)
    fun delete(commands: FilesCommands): CommandHandler = commands.delete

    @Provides
    @IntoMap
    @StringKey(MessageTypes.TRANSFER_SEND)
    fun transferSend(commands: FilesCommands): CommandHandler = commands.transferSend

    @Provides
    @IntoMap
    @StringKey(MessageTypes.TRANSFER_RECEIVE)
    fun transferReceive(commands: FilesCommands): CommandHandler = commands.transferReceive

    @Provides
    @IntoMap
    @StringKey(MessageTypes.TRANSFER_CANCEL)
    fun transferCancel(commands: FilesCommands): EventHandler = commands.transferCancel
}
