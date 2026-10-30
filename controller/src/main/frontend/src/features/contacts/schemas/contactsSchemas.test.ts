import { describe, expect, it } from "vitest";
import { contactFormSchema, contactToForm, emptyContactForm, formToFields, type Contact } from "./contactsSchemas";

const contact: Contact = {
  id: 42,
  lookupKey: "0r42",
  version: "17:3",
  account: { type: "com.google", name: "alice@example.com", label: "Google" },
  readOnly: false,
  starred: true,
  photo: false,
  name: { display: "Dr. Alice B. Example", given: "Alice", middle: "B.", family: "Example", prefix: "Dr.", suffix: null },
  phones: [{ number: "+49 151 2345678", type: "CUSTOM", label: "Boat" }],
  emails: [{ address: "alice@example.com", type: "WORK", label: null }],
  organization: { company: "Example GmbH", title: null },
  note: null,
};

describe("contactFormSchema", () => {
  it("needs a name, number, email or company", () => {
    const empty = { ...emptyContactForm, phones: [] };
    expect(contactFormSchema.safeParse(empty).success).toBe(false);
    expect(contactFormSchema.safeParse({ ...empty, company: "Example GmbH" }).success).toBe(true);
    expect(contactFormSchema.safeParse({ ...empty, given: "  " }).success).toBe(false);
    expect(contactFormSchema.safeParse(emptyContactForm).success).toBe(false);
  });

  it("validates numbers, emails and the limits", () => {
    const base = { ...emptyContactForm, given: "Alice", phones: [] };
    expect(contactFormSchema.safeParse({ ...base, emails: [{ address: "nope", type: "HOME", label: "" }] }).success).toBe(false);
    expect(contactFormSchema.safeParse({ ...base, note: "x".repeat(2001) }).success).toBe(false);
    expect(
      contactFormSchema.safeParse({ ...base, phones: [{ number: "+49\u0007151", type: "MOBILE", label: "" }] }).success,
    ).toBe(false);
    const tooMany = Array.from({ length: 21 }, (_, index) => ({ number: `0151 ${index}`, type: "MOBILE" as const, label: "" }));
    expect(contactFormSchema.safeParse({ ...base, phones: tooMany }).success).toBe(false);
  });
});

describe("contact ↔ form", () => {
  it("round-trips the editable fields and keeps name parts the form doesn't show", () => {
    const form = contactToForm(contact);
    expect(form).toMatchObject({ given: "Alice", family: "Example", company: "Example GmbH", starred: true });

    const fields = formToFields({ ...form, given: "Alicia" }, contact);
    expect(fields.name).toEqual({ given: "Alicia", middle: "B.", family: "Example", prefix: "Dr.", suffix: null });
    expect(fields.phones).toEqual([{ number: "+49 151 2345678", type: "CUSTOM", label: "Boat" }]);
    expect(fields.emails).toEqual([{ address: "alice@example.com", type: "WORK", label: null }]);
    expect(fields.organization).toEqual({ company: "Example GmbH", title: null });
    expect(fields.note).toBeNull();
  });

  it("drops labels of non-custom types and an empty organization", () => {
    const fields = formToFields({
      ...emptyContactForm,
      given: "Bob",
      phones: [{ number: "0151", type: "HOME", label: "stale" }],
    });
    expect(fields.phones[0]?.label).toBeNull();
    expect(fields.organization).toBeNull();
    expect(fields.name.middle).toBeNull();
  });
});
