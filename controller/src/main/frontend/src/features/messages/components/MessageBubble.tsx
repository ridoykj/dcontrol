import { cn } from "@/shared/lib/utils";
import type { Outgoing } from "../lib/outgoingState";
import type { SmsMessage } from "../schemas/smsSchemas";

const timeFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "short", timeStyle: "short" });

function listedStatus(message: SmsMessage): string | null {
  if (message.box === "INBOX") return null;
  if (message.box === "FAILED" || message.status === "FAILED") return "Not sent";
  if (message.box === "OUTBOX" || message.box === "QUEUED" || message.status === "PENDING") return "Sending";
  if (message.box === "DRAFT") return "Draft";
  return message.status === "COMPLETE" ? "Delivered" : "Sent";
}

const outgoingLabels: Record<Outgoing["status"], string> = {
  sending: "Sending to phone…",
  queued: "Sending…",
  sent: "Sent",
  delivered: "Delivered",
  failed: "Not sent",
};

function Bubble({ mine, text, footer, failed }: { mine: boolean; text: string; footer: string; failed?: boolean }) {
  return (
    <li className={cn("flex", mine ? "justify-end" : "justify-start")}>
      <div
        className={cn(
          "max-w-[80%] rounded-2xl px-3 py-2 text-sm",
          mine ? "bg-blue-600 text-white" : "bg-neutral-100 text-neutral-900 dark:bg-neutral-800 dark:text-neutral-100",
          failed && "bg-red-100 text-red-900 dark:bg-red-950 dark:text-red-100",
        )}
      >
        <p className="break-words whitespace-pre-line">{text}</p>
        <p className={cn("mt-1 text-[11px]", mine && !failed ? "text-blue-100" : "text-neutral-500")}>{footer}</p>
      </div>
    </li>
  );
}

export function ListedBubble({ message }: { message: SmsMessage }) {
  const status = listedStatus(message);
  const text = message.kind === "MMS" ? (message.body || message.mmsSummary || "MMS") : (message.body ?? "");
  const footer = [timeFormat.format(new Date(message.date)), message.simLabel, status].filter(Boolean).join(" · ");
  return <Bubble mine={message.box !== "INBOX"} text={text} footer={footer} failed={status === "Not sent"} />;
}

export function OutgoingBubble({ item }: { item: Outgoing }) {
  const footer = [outgoingLabels[item.status], item.error].filter(Boolean).join(" · ");
  return <Bubble mine text={item.text} footer={footer} failed={item.status === "failed"} />;
}
