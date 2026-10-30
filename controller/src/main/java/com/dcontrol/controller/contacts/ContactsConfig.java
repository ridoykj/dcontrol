package com.dcontrol.controller.contacts;

import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.contacts.ContactsProtocol.AccountsCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.AccountsResult;
import com.dcontrol.controller.contacts.ContactsProtocol.ChangedForWeb;
import com.dcontrol.controller.contacts.ContactsProtocol.Contact;
import com.dcontrol.controller.contacts.ContactsProtocol.CreateCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.DeleteCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.DeleteResult;
import com.dcontrol.controller.contacts.ContactsProtocol.GetCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.ListCommand;
import com.dcontrol.controller.contacts.ContactsProtocol.ListResult;
import com.dcontrol.controller.contacts.ContactsProtocol.UpdateCommand;
import com.dcontrol.controller.device.DeviceEventHandler;
import com.dcontrol.controller.device.RelayGuards;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Contacts read and edited live on the phone (ws-v1.md, Phase 3). Nothing is persisted or logged. */
@Configuration(proxyBeanMethods = false)
public class ContactsConfig {

    @Bean
    RelayedCommand<ListCommand, ListResult> contactsList() {
        return RelayedCommand.of(MessageType.CONTACTS_LIST, ListCommand.class, ListResult.class);
    }

    @Bean
    RelayedCommand<GetCommand, Contact> contactsGet() {
        return RelayedCommand.of(MessageType.CONTACTS_GET, GetCommand.class, Contact.class);
    }

    @Bean
    RelayedCommand<AccountsCommand, AccountsResult> contactsAccounts() {
        return RelayedCommand.of(MessageType.CONTACTS_ACCOUNTS, AccountsCommand.class, AccountsResult.class);
    }

    @Bean
    RelayedCommand<CreateCommand, Contact> contactsCreate() {
        return RelayedCommand.of(MessageType.CONTACTS_CREATE, CreateCommand.class, Contact.class);
    }

    @Bean
    RelayedCommand<UpdateCommand, Contact> contactsUpdate() {
        return RelayedCommand.of(MessageType.CONTACTS_UPDATE, UpdateCommand.class, Contact.class);
    }

    /** Deletes propagate to synced accounts (e.g. Google), so each one is audited. */
    @Bean
    RelayedCommand<DeleteCommand, DeleteResult> contactsDelete(RelayGuards guards) {
        return RelayedCommand.of(MessageType.CONTACTS_DELETE, DeleteCommand.class, DeleteResult.class)
                .withBeforeRelay(guards.audited(MessageType.CONTACTS_DELETE));
    }

    @Bean
    DeviceEventHandler contactsChanged(MessageCodec codec, WebSessionRegistry web) {
        return new DeviceEventHandler(MessageType.CONTACTS_CHANGED, (source, envelope) -> {
            codec.payload(envelope, EmptyResult.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.CONTACTS_CHANGED, null,
                    new ChangedForWeb(source.deviceKey())));
        });
    }
}
