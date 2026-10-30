import { Send } from "lucide-react";
import { useState } from "react";
import { Form, Label, TextArea, TextField } from "react-aria-components";
import { SimPicker } from "@/features/devices/components/SimPicker";
import type { Sim } from "@/features/devices/api/simsApi";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { smsParts } from "../lib/smsParts";
import { composeSchema, MAX_SMS_PARTS, MAX_SMS_TEXT } from "../schemas/smsSchemas";

type ComposerProps = {
  sims: Sim[];
  isDisabled?: boolean;
  /** Called with the trimmed text; the composer clears right away and the bubble shows the progress. */
  onSend: (text: string, simId: number | null) => void;
};

/** Message box with the part counter carriers bill by. */
export function Composer({ sims, isDisabled, onSend }: ComposerProps) {
  const [text, setText] = useState("");
  const [simId, setSimId] = useState<number | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const parts = smsParts(text);

  return (
    <Form
      validationBehavior="aria"
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        const parsed = composeSchema.safeParse({ text });
        if (!parsed.success) {
          setProblem(parsed.error.issues[0]?.message ?? "Invalid message");
          return;
        }
        const needed = smsParts(parsed.data.text).parts;
        if (needed > MAX_SMS_PARTS) {
          setProblem(`Too long: ${needed} parts. The phone sends at most ${MAX_SMS_PARTS}.`);
          return;
        }
        setProblem(null);
        onSend(parsed.data.text, simId);
        setText("");
      }}
    >
      <TextField value={text} onChange={setText} isInvalid={problem !== null} maxLength={MAX_SMS_TEXT} className="flex flex-col gap-1">
        <Label className="sr-only">Message</Label>
        <TextArea
          rows={3}
          placeholder="Text message"
          onKeyDown={(event) => {
            if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) event.currentTarget.form?.requestSubmit();
          }}
          className="rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-900"
        />
      </TextField>
      {problem && <span className="text-xs text-red-600">{problem}</span>}
      <div className="flex flex-wrap items-end gap-3">
        <SimPicker sims={sims} value={simId} onChange={setSimId} />
        <span className="text-xs text-neutral-500 tabular-nums" aria-live="polite">
          {parts.parts === 0
            ? `${MAX_SMS_TEXT} characters max`
            : `${parts.parts} ${parts.parts === 1 ? "part" : "parts"} · ${parts.remaining} left${
                parts.encoding === "UCS_2" ? " · Unicode" : ""
              }`}
        </span>
        <Button type="submit" className="ml-auto" isDisabled={isDisabled}>
          <Send aria-hidden className="h-4 w-4" />
          <span>Send</span>
        </Button>
      </div>
    </Form>
  );
}
