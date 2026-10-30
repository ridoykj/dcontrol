import { useState } from "react";
import { Form, Heading } from "react-aria-components";
import { Alert } from "@/shared/components/Alert";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Dialog } from "@/shared/components/ui/shadcn/Dialog";
import { TextField } from "@/shared/components/ui/shadcn/TextField";
import { errorMessage } from "@/shared/api/problem";
import { fileNameSchema } from "../schemas/filesSchemas";

type NameDialogProps = {
  title: string;
  label: string;
  actionLabel: string;
  initialName?: string;
  /** Resolves when done; a rejection is shown in the dialog. */
  onSubmit: (name: string) => Promise<unknown>;
};

/** Asks for a file or folder name (new folder, rename). */
export function NameDialog({ title, label, actionLabel, initialName = "", onSubmit }: NameDialogProps) {
  const [name, setName] = useState(initialName);
  const [invalid, setInvalid] = useState<string | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  return (
    <Dialog>
      {({ close }) => (
        <Form
          validationBehavior="aria"
          className="flex flex-col gap-4"
          onSubmit={async (event) => {
            event.preventDefault();
            const parsed = fileNameSchema.safeParse(name);
            if (!parsed.success) {
              setInvalid(parsed.error.issues[0]?.message ?? "Invalid name");
              return;
            }
            setPending(true);
            setFailure(null);
            try {
              await onSubmit(parsed.data);
              close();
            } catch (error) {
              setFailure(errorMessage(error));
            } finally {
              setPending(false);
            }
          }}
        >
          <Heading slot="title" className="text-xl font-semibold">
            {title}
          </Heading>
          <TextField
            label={label}
            value={name}
            onChange={(value) => {
              setName(value);
              setInvalid(null);
            }}
            isInvalid={invalid !== null}
            errorMessage={invalid ?? undefined}
            autoFocus
          />
          {failure && <Alert tone="error">{failure}</Alert>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onPress={close}>
              Cancel
            </Button>
            <Button type="submit" isPending={pending}>
              {actionLabel}
            </Button>
          </div>
        </Form>
      )}
    </Dialog>
  );
}
