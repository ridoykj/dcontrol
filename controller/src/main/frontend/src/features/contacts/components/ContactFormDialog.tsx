import { zodResolver } from "@hookform/resolvers/zod";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Controller, useFieldArray, useForm, type Control } from "react-hook-form";
import { Heading } from "react-aria-components";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Checkbox } from "@/shared/components/ui/shadcn/Checkbox";
import { Dialog } from "@/shared/components/ui/shadcn/Dialog";
import { Form } from "@/shared/components/ui/shadcn/Form";
import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import { RealtimeError } from "@/shared/ws/client";
import {
  EMAIL_TYPES,
  PHONE_TYPES,
  contactFormSchema,
  emailTypeLabels,
  phoneTypeLabels,
  type Account,
  type ContactForm,
} from "../schemas/contactsSchemas";

type ContactFormDialogProps = {
  title: string;
  initial: ContactForm;
  /** Offered when creating; editing keeps the contact's account. */
  accounts?: Account[];
  onSubmit: (form: ContactForm, account: Account | null) => Promise<unknown>;
};

function accountKey(account: Account): string {
  return account.type && account.name ? `${account.type}\u0000${account.name}` : "phone";
}

function TypeSelect<T extends string>({
  control,
  name,
  options,
  labels,
}: {
  control: Control<ContactForm>;
  name: `phones.${number}.type` | `emails.${number}.type`;
  options: readonly T[];
  labels: Record<T, string>;
}) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field }) => (
        <Select
          aria-label="Type"
          selectedKey={field.value}
          onSelectionChange={(key) => field.onChange(key)}
          className="w-36"
        >
          {options.map((option) => (
            <SelectItem key={option} id={option}>
              {labels[option]}
            </SelectItem>
          ))}
        </Select>
      )}
    />
  );
}

function failureText(error: unknown): string {
  if (error instanceof RealtimeError && error.code === "CONFLICT") {
    return "The contact changed on the phone while you were editing. Close this dialog and open the contact again.";
  }
  if (error instanceof RealtimeError && error.code === "FORBIDDEN") {
    return "This contact belongs to an account the phone doesn't allow editing.";
  }
  return relayErrorMessage(error);
}

/** Create or edit a contact: name, numbers, email addresses, company, note and favorite. */
export function ContactFormDialog({ title, initial, accounts, onSubmit }: ContactFormDialogProps) {
  const form = useForm<ContactForm>({ resolver: zodResolver(contactFormSchema), defaultValues: initial });
  const phones = useFieldArray({ control: form.control, name: "phones" });
  const emails = useFieldArray({ control: form.control, name: "emails" });
  const [account, setAccount] = useState<Account | null>(
    accounts?.find((candidate) => candidate.type !== null && candidate.type !== undefined) ?? accounts?.[0] ?? null,
  );
  const [failure, setFailure] = useState<string | null>(null);

  return (
    <Dialog>
      {({ close }) => (
        <Form
          validationBehavior="aria"
          className="flex flex-col gap-4"
          onSubmit={form.handleSubmit(async (values) => {
            setFailure(null);
            try {
              await onSubmit(values, account);
              close();
            } catch (error) {
              setFailure(failureText(error));
            }
          })}
        >
          <Heading slot="title" className="text-xl font-semibold">
            {title}
          </Heading>

          {accounts && accounts.length > 1 && (
            <Select
              label="Save to"
              selectedKey={account ? accountKey(account) : null}
              onSelectionChange={(key) => setAccount(accounts.find((candidate) => accountKey(candidate) === key) ?? null)}
            >
              {accounts.map((candidate) => (
                <SelectItem key={accountKey(candidate)} id={accountKey(candidate)}>
                  {candidate.name ? `${candidate.label} · ${candidate.name}` : candidate.label}
                </SelectItem>
              ))}
            </Select>
          )}

          <div className="grid gap-3 sm:grid-cols-2">
            <FormTextField control={form.control} name="given" label="First name" autoFocus />
            <FormTextField control={form.control} name="family" label="Last name" />
          </div>

          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">Phone numbers</legend>
            {phones.fields.map((field, index) => (
              <div key={field.id} className="flex items-start gap-2">
                <FormTextField
                  control={form.control}
                  name={`phones.${index}.number`}
                  aria-label={`Phone number ${index + 1}`}
                  type="tel"
                  className="flex-1"
                />
                <TypeSelect control={form.control} name={`phones.${index}.type`} options={PHONE_TYPES} labels={phoneTypeLabels} />
                {form.watch(`phones.${index}.type`) === "CUSTOM" && (
                  <FormTextField control={form.control} name={`phones.${index}.label`} aria-label="Custom label" className="w-28" />
                )}
                <Button variant="quiet" aria-label={`Remove phone number ${index + 1}`} onPress={() => phones.remove(index)}>
                  <Trash2 aria-hidden className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button
              variant="secondary"
              className="self-start"
              isDisabled={phones.fields.length >= 20}
              onPress={() => phones.append({ number: "", type: "MOBILE", label: "" })}
            >
              <Plus aria-hidden className="h-4 w-4" />
              <span>Add number</span>
            </Button>
          </fieldset>

          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">Email addresses</legend>
            {emails.fields.map((field, index) => (
              <div key={field.id} className="flex items-start gap-2">
                <FormTextField
                  control={form.control}
                  name={`emails.${index}.address`}
                  aria-label={`Email address ${index + 1}`}
                  type="email"
                  className="flex-1"
                />
                <TypeSelect control={form.control} name={`emails.${index}.type`} options={EMAIL_TYPES} labels={emailTypeLabels} />
                {form.watch(`emails.${index}.type`) === "CUSTOM" && (
                  <FormTextField control={form.control} name={`emails.${index}.label`} aria-label="Custom label" className="w-28" />
                )}
                <Button variant="quiet" aria-label={`Remove email address ${index + 1}`} onPress={() => emails.remove(index)}>
                  <Trash2 aria-hidden className="h-4 w-4" />
                </Button>
              </div>
            ))}
            <Button
              variant="secondary"
              className="self-start"
              isDisabled={emails.fields.length >= 20}
              onPress={() => emails.append({ address: "", type: "HOME", label: "" })}
            >
              <Plus aria-hidden className="h-4 w-4" />
              <span>Add email</span>
            </Button>
          </fieldset>

          <div className="grid gap-3 sm:grid-cols-2">
            <FormTextField control={form.control} name="company" label="Company" />
            <FormTextField control={form.control} name="title" label="Job title" />
          </div>
          <FormTextField control={form.control} name="note" label="Note" />
          <Controller
            control={form.control}
            name="starred"
            render={({ field }) => (
              <Checkbox isSelected={field.value} onChange={field.onChange}>
                Favorite
              </Checkbox>
            )}
          />

          {failure && <Alert tone="error">{failure}</Alert>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onPress={close}>
              Cancel
            </Button>
            <Button type="submit" isPending={form.formState.isSubmitting}>
              Save
            </Button>
          </div>
        </Form>
      )}
    </Dialog>
  );
}
