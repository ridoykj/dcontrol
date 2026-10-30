import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Mail, MessageSquare, Pencil, Phone, Star, Trash2 } from "lucide-react";
import { useState } from "react";
import { DialogTrigger } from "react-aria-components";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { PlaceCallDialog } from "@/features/devices/components/PlaceCallDialog";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { AlertDialog } from "@/shared/components/ui/shadcn/AlertDialog";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { contactKeys, deleteContacts, getContact, updateContact } from "../api/contactsApi";
import { contactToForm, emailTypeLabels, phoneTypeLabels, type Contact } from "../schemas/contactsSchemas";
import { ContactAvatar } from "./ContactAvatar";
import { ContactFormDialog } from "./ContactFormDialog";

type ContactDetailProps = {
  deviceId: string;
  id: number;
  lookupKey: string;
  onMessage: (number: string) => void;
  onDeleted: () => void;
};

function typeLabel(type: string, label: string | null | undefined, labels: Record<string, string>) {
  return type === "CUSTOM" && label ? label : (labels[type] ?? type);
}

function DeleteContact({ deviceId, contact, onDeleted }: { deviceId: string; contact: Contact; onDeleted: () => void }) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<string | null>(null);
  const synced = contact.account?.type ? contact.account.label : null;

  return (
    <>
      <DialogTrigger>
        <Button variant="quiet">
          <Trash2 aria-hidden className="h-4 w-4" />
          <span>Delete</span>
        </Button>
        <Modal>
          <AlertDialog
            title={`Delete ${contact.name.display || "this contact"}?`}
            variant="destructive"
            actionLabel="Delete"
            onAction={() => {
              setFailure(null);
              deleteContacts(client, deviceId, [{ id: contact.id, lookupKey: contact.lookupKey }])
                .then((result) => {
                  const failed = result.failures[0];
                  if (failed) throw new Error(failed.message ?? failed.code);
                  void queryClient.invalidateQueries({ queryKey: contactKeys.all(deviceId) });
                  onDeleted();
                })
                .catch((error) => setFailure(relayErrorMessage(error)));
            }}
          >
            The contact is deleted on the phone.
            {synced && ` It is synced with ${synced}, so it is deleted there too.`} This can't be undone from here.
          </AlertDialog>
        </Modal>
      </DialogTrigger>
      {failure && <Alert tone="error">{failure}</Alert>}
    </>
  );
}

/** One contact read live from the phone, with edit, delete, call and message actions. */
export function ContactDetail({ deviceId, id, lookupKey, onMessage, onDeleted }: ContactDetailProps) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const contact = useQuery({
    queryKey: contactKeys.detail(deviceId, id),
    queryFn: () => getContact(client, deviceId, id, lookupKey),
    retry: false,
  });
  const [calling, setCalling] = useState<string | null>(null);

  if (contact.isPending) return <ProgressBar aria-label="Loading contact" isIndeterminate />;
  if (contact.isError) {
    const permission = missingPermission(contact.error);
    return permission ? <PermissionRequired permission={permission} /> : <Alert tone="error">{relayErrorMessage(contact.error)}</Alert>;
  }
  const data = contact.data;

  return (
    <Card className="flex flex-col gap-4">
      <div className="flex items-center gap-4">
        <ContactAvatar deviceId={deviceId} contact={{ ...data, name: data.name.display }} size="lg" />
        <div className="min-w-0 flex-1">
          <h2 className="flex items-center gap-2 truncate text-xl font-semibold">
            {data.name.display || "No name"}
            {data.starred && <Star aria-label="Favorite" className="h-4 w-4 fill-amber-400 text-amber-400" />}
          </h2>
          {data.organization?.company && (
            <p className="truncate text-sm text-neutral-500">
              {[data.organization.title, data.organization.company].filter(Boolean).join(", ")}
            </p>
          )}
          <p className="text-xs text-neutral-500">{data.account ? data.account.label : "Phone only"}</p>
        </div>
      </div>

      {data.readOnly && <Alert>This contact comes from an account the phone doesn't allow editing.</Alert>}

      <ul className="flex flex-col gap-2" aria-label="Phone numbers">
        {data.phones.map((phone, index) => (
          <li key={index} className="flex flex-wrap items-center gap-2">
            <Phone aria-hidden className="h-4 w-4 text-neutral-500" />
            <span className="tabular-nums">{phone.number}</span>
            <span className="text-xs text-neutral-500">{typeLabel(phone.type, phone.label, phoneTypeLabels)}</span>
            <span className="ml-auto flex gap-1">
              <Button variant="quiet" aria-label={`Call ${phone.number}`} onPress={() => setCalling(phone.number)}>
                <Phone aria-hidden className="h-4 w-4" />
              </Button>
              <Button variant="quiet" aria-label={`Message ${phone.number}`} onPress={() => onMessage(phone.number)}>
                <MessageSquare aria-hidden className="h-4 w-4" />
              </Button>
            </span>
          </li>
        ))}
        {data.emails.map((email, index) => (
          <li key={`e${index}`} className="flex items-center gap-2">
            <Mail aria-hidden className="h-4 w-4 text-neutral-500" />
            <a href={`mailto:${email.address}`} className="truncate text-blue-600 hover:underline">
              {email.address}
            </a>
            <span className="text-xs text-neutral-500">{typeLabel(email.type, email.label, emailTypeLabels)}</span>
          </li>
        ))}
      </ul>
      {data.note && <p className="text-sm whitespace-pre-line text-neutral-700 dark:text-neutral-300">{data.note}</p>}

      {!data.readOnly && (
        <div className="flex flex-wrap justify-end gap-2">
          <DeleteContact deviceId={deviceId} contact={data} onDeleted={onDeleted} />
          <DialogTrigger>
            <Button variant="secondary">
              <Pencil aria-hidden className="h-4 w-4" />
              <span>Edit</span>
            </Button>
            <Modal>
              <ContactFormDialog
                title="Edit contact"
                initial={contactToForm(data)}
                onSubmit={async (form) => {
                  try {
                    const saved = await updateContact(client, deviceId, data, form);
                    queryClient.setQueryData(contactKeys.detail(deviceId, id), saved);
                  } finally {
                    // Refresh after a CONFLICT too, so reopening the dialog edits the phone's current version.
                    void queryClient.invalidateQueries({ queryKey: contactKeys.all(deviceId) });
                  }
                }}
              />
            </Modal>
          </DialogTrigger>
        </div>
      )}

      <Modal isOpen={calling !== null} onOpenChange={(open) => !open && setCalling(null)}>
        {calling && <PlaceCallDialog deviceId={deviceId} number={calling} name={data.name.display} />}
      </Modal>
    </Card>
  );
}
