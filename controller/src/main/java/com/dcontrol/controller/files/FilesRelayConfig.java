package com.dcontrol.controller.files;

import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.files.FilesProtocol.DeleteCommand;
import com.dcontrol.controller.files.FilesProtocol.DeleteResult;
import com.dcontrol.controller.files.FilesProtocol.EntryResult;
import com.dcontrol.controller.files.FilesProtocol.ListCommand;
import com.dcontrol.controller.files.FilesProtocol.ListResult;
import com.dcontrol.controller.files.FilesProtocol.MkdirCommand;
import com.dcontrol.controller.files.FilesProtocol.RenameCommand;
import com.dcontrol.controller.files.FilesProtocol.RootsCommand;
import com.dcontrol.controller.files.FilesProtocol.RootsResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** File-manager commands relayed from the browser to the phone. Contents move as transfers, not here. */
@Configuration(proxyBeanMethods = false)
public class FilesRelayConfig {

    @Bean
    RelayedCommand<RootsCommand, RootsResult> filesRoots() {
        return RelayedCommand.of(MessageType.FILES_ROOTS, RootsCommand.class, RootsResult.class);
    }

    @Bean
    RelayedCommand<ListCommand, ListResult> filesList() {
        return RelayedCommand.of(MessageType.FILES_LIST, ListCommand.class, ListResult.class);
    }

    @Bean
    RelayedCommand<MkdirCommand, EntryResult> filesMkdir() {
        return RelayedCommand.of(MessageType.FILES_MKDIR, MkdirCommand.class, EntryResult.class);
    }

    @Bean
    RelayedCommand<RenameCommand, EntryResult> filesRename() {
        return RelayedCommand.of(MessageType.FILES_RENAME, RenameCommand.class, EntryResult.class);
    }

    @Bean
    RelayedCommand<DeleteCommand, DeleteResult> filesDelete() {
        return RelayedCommand.of(MessageType.FILES_DELETE, DeleteCommand.class, DeleteResult.class);
    }
}
