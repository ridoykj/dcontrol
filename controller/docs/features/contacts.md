# Contacts

The Contacts tab reads the phone's address book live and can create, edit and delete contacts. **Nothing is
stored on the controller**: every list, search and detail view asks the phone, and nothing is logged except one
audit line per delete (type, device, user).

## Flow

| Step | Message |
|---|---|
| Open the tab, search | `contacts.list {query, cursor}` → `{items, nextCursor}`, sorted by display name, "Load more" pages on |
| Select a contact | `contacts.get {id, lookupKey}` → `Contact` |
| New contact | `contacts.accounts {}` (where it can be saved), then `contacts.create {account, fields}` |
| Edit | `contacts.update {id, lookupKey, version, fields}`; `CONFLICT` when the contact changed on the phone meanwhile |
| Delete | `contacts.delete {items}` after a confirmation that warns about synced accounts |
| Photo | `GET /api/devices/{id}/contacts/{contactId}/photo?lookupKey=…`, pulled from the phone per request |
| Something changed on the phone | `contacts.changed` → the web refetches what it shows |

- **Lookup by key.** Contact ids change when Android re-aggregates contacts. The phone resolves every
  `get`, `update` and `delete` by `lookupKey` first.
- **What an edit changes.** The web edits first and last name, phone numbers, email addresses, company, job title,
  note and favorite. Android may merge several account copies into one contact; the phone removes those kinds
  of data from every writable copy and writes the new values to the first writable one (skipping numbers and
  emails a read-only copy already shows, so edits never duplicate them). Middle name, prefix, suffix,
  addresses, events, groups and the photo stay as they are. Number types Android has beyond the list (car,
  assistant…) arrive as `CUSTOM` with Android's label, so editing keeps their meaning.
- **Read-only contacts.** Contacts from accounts whose sync adapter forbids edits show `readOnly`; the web hides
  Edit and Delete, and the phone answers `FORBIDDEN` anyway.
- **Deletes propagate.** Deleting a Google (or other synced) contact on the phone deletes it from that account
  too. The dialog says so.
- **Photos** never touch the WebSocket or the controller's disk. The controller opens a single-use in-memory
  slot, sends `contacts.photo {uploadId, id, lookupKey}`, and the phone uploads a JPEG of at most 256 KiB to
  `PUT /api/device-api/blobs/{uploadId}`. The browser caches it for five minutes. Editing photos isn't supported.

## Android permissions

| Permission | Kind | Why |
|---|---|---|
| `READ_CONTACTS` | Runtime ("Contacts" group) | List, search, details, photos; also names in SMS threads |
| `WRITE_CONTACTS` | Runtime (same group, granted together) | Create, edit, delete |

- The phone answers `PERMISSION_REQUIRED` with `details.permission = CONTACTS` when either is missing; the web
  shows how to grant it from the **Contacts** card on the phone.
- Accounts come from the contacts provider (`RawContacts`), so no `GET_ACCOUNTS` permission is needed.
- No foreground-service type is added: commands run in the existing `connectedDevice` `ConnectionService`.
