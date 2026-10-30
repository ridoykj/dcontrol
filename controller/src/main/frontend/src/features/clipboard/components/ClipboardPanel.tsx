import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Form, Label, TextArea, TextField } from "react-aria-components";
import { z } from "zod";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { formatRelativeTime } from "@/shared/lib/utils";
import type { Requester } from "@/shared/ws/client";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";

export const MAX_CLIPBOARD = 16_000;
const HISTORY = 20;

const clipboardChangedSchema = z.object({ deviceId: z.string(), text: z.string(), receivedAt: z.string() });

type Clip = { id: number; text: string; receivedAt: string };

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      variant="secondary"
      onPress={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        } catch {
          setCopied(false);
        }
      }}
    >
      {copied ? <Check aria-hidden className="h-4 w-4" /> : <Copy aria-hidden className="h-4 w-4" />}
      {copied ? "Copied" : "Copy"}
    </Button>
  );
}

/**
 * Text clipboard in both directions. Sending sets the phone's clipboard; the phone sends its clipboard
 * only when the user asks it to (Android blocks background clipboard reads). History lives in this tab only.
 */
export function ClipboardPanel({ client, deviceId, online }: { client: Requester; deviceId: string; online: boolean }) {
  const [text, setText] = useState("");
  const [status, setStatus] = useState<{ tone: "success" | "error"; message: string } | null>(null);
  const [pending, setPending] = useState(false);
  const [clips, setClips] = useState<Clip[]>([]);

  useRealtimeEvent("clipboard.changed", clipboardChangedSchema, (event) => {
    if (event.deviceId !== deviceId) return;
    setClips((previous) => [{ id: Date.now(), text: event.text, receivedAt: event.receivedAt }, ...previous].slice(0, HISTORY));
  });

  const tooLong = text.length > MAX_CLIPBOARD;

  return (
    <div className="flex flex-col gap-4">
      <Card className="flex flex-col gap-3">
        <h2 className="font-medium">Send to the phone</h2>
        <Form
          validationBehavior="aria"
          className="flex flex-col gap-3"
          onSubmit={async (event) => {
            event.preventDefault();
            if (text.length === 0 || tooLong) return;
            setPending(true);
            setStatus(null);
            try {
              await client.request("clipboard.set", { deviceId, text });
              setStatus({ tone: "success", message: "Copied to the phone's clipboard." });
            } catch (error) {
              setStatus({ tone: "error", message: relayErrorMessage(error) });
            } finally {
              setPending(false);
            }
          }}
        >
          <TextField value={text} onChange={setText} isInvalid={tooLong} className="flex flex-col gap-1">
            <Label className="text-sm">Text</Label>
            <TextArea
              rows={4}
              className="rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-900"
            />
          </TextField>
          <div className="flex items-center justify-between gap-3">
            <span className={tooLong ? "text-xs text-red-600" : "text-xs text-neutral-500"}>
              {text.length.toLocaleString()} / {MAX_CLIPBOARD.toLocaleString()} characters
            </span>
            <Button type="submit" isDisabled={!online || text.length === 0 || tooLong} isPending={pending}>
              Send to phone
            </Button>
          </div>
          {status && <Alert tone={status.tone}>{status.message}</Alert>}
        </Form>
      </Card>

      <Card className="flex flex-col gap-3">
        <h2 className="font-medium">From the phone</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          Android only lets apps read the clipboard when you ask. On the phone, use the “Send clipboard” tile, the
          action on the dcontrol notification, or the button in the app.
        </p>
        {clips.length === 0 && <p className="text-sm text-neutral-500">Nothing received yet.</p>}
        <ul className="flex flex-col gap-2" aria-label="Received clipboard text">
          {clips.map((clip) => (
            <li key={clip.id} className="flex items-start gap-3 rounded-lg border border-neutral-200 p-3 dark:border-neutral-800">
              <p className="min-w-0 flex-1 text-sm whitespace-pre-wrap break-words">{clip.text}</p>
              <div className="flex shrink-0 flex-col items-end gap-1">
                <CopyButton text={clip.text} />
                <span className="text-xs text-neutral-500">{formatRelativeTime(clip.receivedAt)}</span>
              </div>
            </li>
          ))}
        </ul>
      </Card>
    </div>
  );
}
