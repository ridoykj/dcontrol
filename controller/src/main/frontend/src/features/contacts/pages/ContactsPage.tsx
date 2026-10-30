import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, RefreshCw, Star } from "lucide-react";
import { useEffect, useState } from "react";
import { DialogTrigger } from "react-aria-components";
import { z } from "zod";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { TextField } from "@/shared/components/ui/shadcn/TextField";
import { cn } from "@/shared/lib/utils";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import { useRelayedPages } from "@/shared/ws/useRelayedPages";
import { contactKeys, createContact, listAccounts } from "../api/contactsApi";
import { ContactAvatar } from "../components/ContactAvatar";
import { ContactDetail } from "../components/ContactDetail";
import { ContactFormDialog } from "../components/ContactFormDialog";
import { contactsPageSchema, emptyContactForm } from "../schemas/contactsSchemas";

const changedSchema = z.object({ deviceId: z.string() });

export type SelectedContact = { id: number; lookupKey: string };

type ContactsPageProps = {
  deviceId: string;
  query: string;
  selected: SelectedContact | null;
  onQueryChange: (query: string) => void;
  onSelect: (contact: SelectedContact | null) => void;
  onMessage: (number: string) => void;
};

/** The phone's contacts, read live: search, details, create, edit, delete. */
export function ContactsPage({ deviceId, query, selected, onQueryChange, onSelect, onMessage }: ContactsPageProps) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const [search, setSearch] = useState(query);

  useEffect(() => {
    const timer = setTimeout(() => {
      if (search.trim() !== query) onQueryChange(search.trim());
    }, 300);
    return () => clearTimeout(timer);
  }, [search, query, onQueryChange]);

  const pages = useRelayedPages({
    queryKey: contactKeys.list(deviceId, query),
    type: "contacts.list",
    payload: { deviceId, query: query || null },
    schema: contactsPageSchema,
    enabled: online,
  });
  const accounts = useQuery({
    queryKey: contactKeys.accounts(deviceId),
    queryFn: () => listAccounts(client, deviceId),
    enabled: online,
    retry: false,
  });

  useRealtimeEvent("contacts.changed", changedSchema, (event) => {
    if (event.deviceId === deviceId) void queryClient.invalidateQueries({ queryKey: contactKeys.all(deviceId) });
  });

  if (!device.isSuccess) return null;
  if (!online) return <Alert>The phone is offline. Contacts are read live from the phone while it is connected.</Alert>;

  const permission = missingPermission(pages.error);
  if (permission) return <PermissionRequired permission={permission} />;

  const contacts = pages.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
      <div className="flex flex-col gap-3">
        <div className="flex items-end gap-2">
          <TextField
            label="Search"
            type="search"
            value={search}
            onChange={setSearch}
            maxLength={100}
            className="flex-1"
          />
          <Button variant="secondary" aria-label="Reload contacts" onPress={() => void pages.refetch()}>
            <RefreshCw aria-hidden className="h-4 w-4" />
          </Button>
          <DialogTrigger>
            <Button>
              <Plus aria-hidden className="h-4 w-4" />
              <span>New</span>
            </Button>
            <Modal>
              <ContactFormDialog
                title="New contact"
                initial={emptyContactForm}
                accounts={accounts.data}
                onSubmit={async (form, account) => {
                  const target = account?.type && account.name ? { type: account.type, name: account.name } : null;
                  const created = await createContact(client, deviceId, target, form);
                  void queryClient.invalidateQueries({ queryKey: contactKeys.all(deviceId) });
                  onSelect({ id: created.id, lookupKey: created.lookupKey });
                }}
              />
            </Modal>
          </DialogTrigger>
        </div>

        {pages.isPending && <ProgressBar aria-label="Loading contacts" isIndeterminate />}
        {pages.isError && <Alert tone="error">{relayErrorMessage(pages.error)}</Alert>}
        {pages.isSuccess && contacts.length === 0 && (
          <p className="text-sm text-neutral-500">{query ? "No contacts match." : "The phone has no contacts."}</p>
        )}
        <ul aria-label="Contacts" className="flex flex-col">
          {contacts.map((contact) => {
            const active = selected?.id === contact.id;
            return (
              <li key={contact.id}>
                <button
                  type="button"
                  aria-current={active ? "true" : undefined}
                  onClick={() => onSelect({ id: contact.id, lookupKey: contact.lookupKey })}
                  className={cn(
                    "flex w-full items-center gap-3 rounded-lg px-2 py-2 text-left hover:bg-neutral-100 dark:hover:bg-neutral-800",
                    active && "bg-blue-50 dark:bg-blue-950",
                  )}
                >
                  <ContactAvatar deviceId={deviceId} contact={contact} />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1 truncate font-medium">
                      {contact.name || contact.phones[0] || contact.emails[0] || "No name"}
                      {contact.starred && <Star aria-label="Favorite" className="h-3 w-3 fill-amber-400 text-amber-400" />}
                    </span>
                    <span className="block truncate text-xs text-neutral-500 tabular-nums">
                      {contact.phones[0] ?? contact.emails[0] ?? ""}
                    </span>
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        {pages.hasNextPage && (
          <Button variant="secondary" isPending={pages.isFetchingNextPage} onPress={() => void pages.fetchNextPage()}>
            Load more
          </Button>
        )}
      </div>

      <div>
        {selected ? (
          <ContactDetail
            key={selected.id}
            deviceId={deviceId}
            id={selected.id}
            lookupKey={selected.lookupKey}
            onMessage={onMessage}
            onDeleted={() => onSelect(null)}
          />
        ) : (
          <p className="text-sm text-neutral-500">Select a contact to see its details.</p>
        )}
      </div>
    </div>
  );
}
