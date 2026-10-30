package com.dcontrol.controller.contacts;

import com.dcontrol.controller.common.validation.PageCursor;
import com.dcontrol.controller.common.validation.PhoneNumber;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** {@code contacts.*} payloads (ws-v1.md, Phase 3). Phone results carry the same rules as browser input. */
public final class ContactsProtocol {

    public static final int MAX_NAME = 200;
    public static final int MAX_EMAIL = 254;
    public static final int MAX_NOTE = 2000;
    public static final int MAX_ENTRIES = 20;

    private ContactsProtocol() {
    }

    public enum PhoneType { MOBILE, HOME, WORK, MAIN, WORK_MOBILE, FAX, PAGER, OTHER, CUSTOM }

    public enum EmailType { HOME, WORK, MOBILE, OTHER, CUSTOM }

    /** Where a contact lives; both null for contacts stored on the phone only. */
    public record AccountRef(@Size(min = 1, max = 255) String type, @Size(min = 1, max = 255) String name) {

        @JsonIgnore
        @AssertTrue(message = "type and name must both be set or both be null")
        public boolean isConsistent() {
            return (type == null) == (name == null);
        }
    }

    public record Account(
            @Size(min = 1, max = 255) String type,
            @Size(min = 1, max = 255) String name,
            @NotBlank @Size(max = 100) String label) {

        @JsonIgnore
        @AssertTrue(message = "type and name must both be set or both be null")
        public boolean isConsistent() {
            return (type == null) == (name == null);
        }
    }

    public record Name(
            @Size(max = MAX_NAME) String given,
            @Size(max = MAX_NAME) String middle,
            @Size(max = MAX_NAME) String family,
            @Size(max = MAX_NAME) String prefix,
            @Size(max = MAX_NAME) String suffix) {

        @JsonIgnore
        boolean hasAny() {
            return Stream.of(given, middle, family, prefix, suffix).anyMatch(ContactsProtocol::notBlank);
        }
    }

    public record DisplayedName(
            @NotNull @Size(max = MAX_NAME) String display,
            @Size(max = MAX_NAME) String given,
            @Size(max = MAX_NAME) String middle,
            @Size(max = MAX_NAME) String family,
            @Size(max = MAX_NAME) String prefix,
            @Size(max = MAX_NAME) String suffix) {
    }

    public record Phone(@NotNull @PhoneNumber String number, @NotNull PhoneType type, @Size(max = 100) String label) {
    }

    public record Email(
            @NotBlank @Size(max = MAX_EMAIL) String address, @NotNull EmailType type, @Size(max = 100) String label) {
    }

    public record Organization(@Size(max = MAX_NAME) String company, @Size(max = MAX_NAME) String title) {
    }

    /** What a browser may set on a contact. */
    public record ContactFields(
            @NotNull @Valid Name name,
            @NotNull @Size(max = MAX_ENTRIES) List<@NotNull @Valid Phone> phones,
            @NotNull @Size(max = MAX_ENTRIES) List<@NotNull @Valid Email> emails,
            @Valid Organization organization,
            @Size(max = MAX_NOTE) String note,
            @NotNull Boolean starred) {

        @JsonIgnore
        @AssertTrue(message = "needs a name, phone, email or company")
        public boolean isNotEmpty() {
            return name == null || phones == null || emails == null
                    || name.hasAny() || !phones.isEmpty() || !emails.isEmpty()
                    || (organization != null && notBlank(organization.company()));
        }
    }

    public record Contact(
            @NotNull @PositiveOrZero Long id,
            @NotBlank @Size(max = 512) String lookupKey,
            @NotBlank @Size(max = 256) String version,
            @Valid Account account,
            @NotNull Boolean readOnly,
            @NotNull Boolean starred,
            @NotNull Boolean photo,
            @NotNull @Valid DisplayedName name,
            @NotNull @Size(max = MAX_ENTRIES) List<@NotNull @Valid Phone> phones,
            @NotNull @Size(max = MAX_ENTRIES) List<@NotNull @Valid Email> emails,
            @Valid Organization organization,
            @Size(max = MAX_NOTE) String note) {
    }

    public record ContactSummary(
            @NotNull @PositiveOrZero Long id,
            @NotBlank @Size(max = 512) String lookupKey,
            @NotNull @Size(max = MAX_NAME) String name,
            @NotNull Boolean starred,
            @NotNull Boolean photo,
            @NotNull @Size(max = 3) List<@NotNull @PhoneNumber String> phones,
            @NotNull @Size(max = 1) List<@NotBlank @Size(max = MAX_EMAIL) String> emails) {
    }

    public record ContactRef(@NotNull @PositiveOrZero Long id, @NotBlank @Size(max = 512) String lookupKey) {
    }

    public record ListCommand(@NotNull UUID deviceId, @Size(max = 100) String query, @PageCursor String cursor)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new ListToDevice(query, cursor);
        }
    }

    public record ListToDevice(String query, String cursor) {
    }

    public record ListResult(
            @NotNull @Size(max = 500) List<@NotNull @Valid ContactSummary> items, @PageCursor String nextCursor) {
    }

    public record GetCommand(@NotNull UUID deviceId, @NotNull @PositiveOrZero Long id,
                             @NotBlank @Size(max = 512) String lookupKey) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new ContactRef(id, lookupKey);
        }
    }

    public record AccountsCommand(@NotNull UUID deviceId) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of();
        }
    }

    public record AccountsResult(@NotNull @Size(max = 32) List<@NotNull @Valid Account> accounts) {
    }

    public record CreateCommand(@NotNull UUID deviceId, @Valid AccountRef account, @NotNull @Valid ContactFields fields)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new CreateToDevice(account, fields);
        }
    }

    public record CreateToDevice(AccountRef account, ContactFields fields) {
    }

    public record UpdateCommand(
            @NotNull UUID deviceId,
            @NotNull @PositiveOrZero Long id,
            @NotBlank @Size(max = 512) String lookupKey,
            @NotBlank @Size(max = 256) String version,
            @NotNull @Valid ContactFields fields) implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return new UpdateToDevice(id, lookupKey, version, fields);
        }
    }

    public record UpdateToDevice(Long id, String lookupKey, String version, ContactFields fields) {
    }

    public record DeleteCommand(@NotNull UUID deviceId, @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid ContactRef> items)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("items", items);
        }
    }

    public record DeleteFailure(
            @NotNull @PositiveOrZero Long id, @NotBlank @Size(max = 64) String code, @Size(max = 200) String message) {
    }

    public record DeleteResult(
            @NotNull @PositiveOrZero Integer deleted, @NotNull @Size(max = 100) List<@NotNull @Valid DeleteFailure> failures) {
    }

    /** Controller → phone: upload this contact's photo into the blob slot {@code uploadId}. */
    public record PhotoCommand(UUID uploadId, long id, String lookupKey) {
    }

    /** {@code contacts.changed} for the browser. */
    public record ChangedForWeb(UUID deviceId) {
    }

    static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
