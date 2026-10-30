import type { Requester } from "@/shared/ws/client";
import {
  accountsSchema,
  contactSchema,
  deleteResultSchema,
  formToFields,
  type Contact,
  type ContactForm,
} from "../schemas/contactsSchemas";

export const contactKeys = {
  all: (deviceId: string) => ["phone", deviceId, "contacts"] as const,
  list: (deviceId: string, query: string) => ["phone", deviceId, "contacts", "list", query] as const,
  detail: (deviceId: string, id: number) => ["phone", deviceId, "contacts", "detail", id] as const,
  accounts: (deviceId: string) => ["phone", deviceId, "contacts", "accounts"] as const,
  photo: (deviceId: string, id: number) => ["phone", deviceId, "contact-photo", id] as const,
};

export async function getContact(client: Requester, deviceId: string, id: number, lookupKey: string) {
  return contactSchema.parse((await client.request("contacts.get", { deviceId, id, lookupKey })).payload);
}

export async function listAccounts(client: Requester, deviceId: string) {
  return accountsSchema.parse((await client.request("contacts.accounts", { deviceId })).payload).accounts;
}

export async function createContact(
  client: Requester,
  deviceId: string,
  account: { type: string; name: string } | null,
  form: ContactForm,
): Promise<Contact> {
  const payload = { deviceId, account, fields: formToFields(form) };
  return contactSchema.parse((await client.request("contacts.create", payload)).payload);
}

/** Fails with CONFLICT when the contact changed on the phone since {@code contact} was read. */
export async function updateContact(client: Requester, deviceId: string, contact: Contact, form: ContactForm) {
  const payload = {
    deviceId,
    id: contact.id,
    lookupKey: contact.lookupKey,
    version: contact.version,
    fields: formToFields(form, contact),
  };
  return contactSchema.parse((await client.request("contacts.update", payload)).payload);
}

export async function deleteContacts(client: Requester, deviceId: string, items: { id: number; lookupKey: string }[]) {
  return deleteResultSchema.parse((await client.request("contacts.delete", { deviceId, items })).payload);
}

export function contactPhotoPath(deviceId: string, id: number, lookupKey: string) {
  return `/api/devices/${encodeURIComponent(deviceId)}/contacts/${id}/photo?lookupKey=${encodeURIComponent(lookupKey)}`;
}
