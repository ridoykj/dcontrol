import { z } from "zod";

export const PHONE_TYPES = ["MOBILE", "HOME", "WORK", "MAIN", "WORK_MOBILE", "FAX", "PAGER", "OTHER", "CUSTOM"] as const;
export const EMAIL_TYPES = ["HOME", "WORK", "MOBILE", "OTHER", "CUSTOM"] as const;

export const phoneTypeLabels: Record<(typeof PHONE_TYPES)[number], string> = {
  MOBILE: "Mobile",
  HOME: "Home",
  WORK: "Work",
  MAIN: "Main",
  WORK_MOBILE: "Work mobile",
  FAX: "Fax",
  PAGER: "Pager",
  OTHER: "Other",
  CUSTOM: "Custom",
};

export const emailTypeLabels: Record<(typeof EMAIL_TYPES)[number], string> = {
  HOME: "Home",
  WORK: "Work",
  MOBILE: "Mobile",
  OTHER: "Other",
  CUSTOM: "Custom",
};

const accountSchema = z.object({ type: z.string().nullish(), name: z.string().nullish(), label: z.string() });

export const contactSummarySchema = z.object({
  id: z.number(),
  lookupKey: z.string(),
  name: z.string(),
  starred: z.boolean(),
  photo: z.boolean(),
  phones: z.array(z.string()),
  emails: z.array(z.string()),
});

export const contactsPageSchema = z.object({
  items: z.array(contactSummarySchema),
  nextCursor: z.string().nullish(),
});

export const contactSchema = z.object({
  id: z.number(),
  lookupKey: z.string(),
  version: z.string(),
  account: accountSchema.nullish(),
  readOnly: z.boolean(),
  starred: z.boolean(),
  photo: z.boolean(),
  name: z.object({
    display: z.string(),
    given: z.string().nullish(),
    middle: z.string().nullish(),
    family: z.string().nullish(),
    prefix: z.string().nullish(),
    suffix: z.string().nullish(),
  }),
  phones: z.array(z.object({ number: z.string(), type: z.enum(PHONE_TYPES), label: z.string().nullish() })),
  emails: z.array(z.object({ address: z.string(), type: z.enum(EMAIL_TYPES), label: z.string().nullish() })),
  organization: z.object({ company: z.string().nullish(), title: z.string().nullish() }).nullish(),
  note: z.string().nullish(),
});

export const accountsSchema = z.object({ accounts: z.array(accountSchema) });

export const deleteResultSchema = z.object({
  deleted: z.number(),
  failures: z.array(z.object({ id: z.number(), code: z.string(), message: z.string().nullish() })),
});

export type ContactSummary = z.infer<typeof contactSummarySchema>;
export type Contact = z.infer<typeof contactSchema>;
export type Account = z.infer<typeof accountSchema>;

const optionalText = (max: number) => z.string().trim().max(max, `Use at most ${max} characters`);

/** Editable fields, validated in the browser before they reach the phone (limits from ws-v1.md). */
export const contactFormSchema = z
  .object({
    given: optionalText(200),
    family: optionalText(200),
    phones: z
      .array(
        z.object({
          number: z
            .string()
            .trim()
            .min(1, "Enter a number")
            .max(64, "Use at most 64 characters")
            .regex(/^[^\p{Cc}]*$/u, "Remove control characters"),
          type: z.enum(PHONE_TYPES),
          label: optionalText(100),
        }),
      )
      .max(20, "At most 20 numbers"),
    emails: z
      .array(
        z.object({
          address: z.string().trim().pipe(z.email("Enter a valid email").max(254, "Use at most 254 characters")),
          type: z.enum(EMAIL_TYPES),
          label: optionalText(100),
        }),
      )
      .max(20, "At most 20 email addresses"),
    company: optionalText(200),
    title: optionalText(200),
    note: optionalText(2000),
    starred: z.boolean(),
  })
  .refine(
    (form) => Boolean(form.given || form.family || form.company) || form.phones.length > 0 || form.emails.length > 0,
    { message: "Enter a name, number, email or company", path: ["given"] },
  );

export type ContactForm = z.infer<typeof contactFormSchema>;

export const emptyContactForm: ContactForm = {
  given: "",
  family: "",
  phones: [{ number: "", type: "MOBILE", label: "" }],
  emails: [],
  company: "",
  title: "",
  note: "",
  starred: false,
};

/** Prefills the form; middle name, prefix and suffix are kept on the phone because the form doesn't show them. */
export function contactToForm(contact: Contact): ContactForm {
  return {
    given: contact.name.given ?? "",
    family: contact.name.family ?? "",
    phones: contact.phones.map((phone) => ({ number: phone.number, type: phone.type, label: phone.label ?? "" })),
    emails: contact.emails.map((email) => ({ address: email.address, type: email.type, label: email.label ?? "" })),
    company: contact.organization?.company ?? "",
    title: contact.organization?.title ?? "",
    note: contact.note ?? "",
    starred: contact.starred,
  };
}

const orNull = (value: string) => (value === "" ? null : value);

/** The {@code ContactFields} wire shape; {@code previous} keeps name parts the form doesn't edit. */
export function formToFields(form: ContactForm, previous?: Contact) {
  return {
    name: {
      given: orNull(form.given),
      middle: previous?.name.middle ?? null,
      family: orNull(form.family),
      prefix: previous?.name.prefix ?? null,
      suffix: previous?.name.suffix ?? null,
    },
    phones: form.phones.map((phone) => ({
      number: phone.number,
      type: phone.type,
      label: phone.type === "CUSTOM" ? orNull(phone.label) : null,
    })),
    emails: form.emails.map((email) => ({
      address: email.address,
      type: email.type,
      label: email.type === "CUSTOM" ? orNull(email.label) : null,
    })),
    organization: form.company || form.title ? { company: orNull(form.company), title: orNull(form.title) } : null,
    note: orNull(form.note),
    starred: form.starred,
  };
}
