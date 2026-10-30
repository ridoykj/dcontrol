import { ArrowDownToLine, ArrowUpFromLine, X } from "lucide-react";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { formatBytes } from "@/shared/lib/utils";
import type { TransferItem } from "../lib/transfersState";
import { isOpen, type Transfer } from "../schemas/filesSchemas";

function describe(item: TransferItem): string {
  const { transfer } = item;
  if (item.error) return item.error;
  const toPhone = transfer.direction === "TO_DEVICE";
  switch (transfer.state) {
    case "RECEIVING":
      return toPhone ? "Uploading to the server" : "Phone is sending";
    case "READY":
      return toPhone ? "Sending to the phone" : "Ready to save";
    case "DELIVERED":
      return toPhone ? "Saved on the phone" : "Downloaded";
    case "FAILED":
      return `Failed${transfer.errorCode ? ` (${transfer.errorCode})` : ""}`;
    case "CANCELLED":
      return "Cancelled";
    case "EXPIRED":
      return "Expired";
  }
}

function progressOf(item: TransferItem): number | null {
  const size = item.transfer.size;
  if (!size || !isOpen(item.transfer.state)) return null;
  if (item.transfer.state === "READY" && item.transfer.direction === "FROM_DEVICE") return null;
  return Math.min(100, Math.round((item.progressBytes / size) * 100));
}

type TransfersPanelProps = {
  items: TransferItem[];
  onCancel: (id: string) => void;
  onSave: (transfer: Transfer) => void;
  onDismiss: (id: string) => void;
};

export function TransfersPanel({ items, onCancel, onSave, onDismiss }: TransfersPanelProps) {
  if (items.length === 0) return null;
  return (
    <Card className="flex flex-col gap-3">
      <h2 className="font-medium">Transfers</h2>
      <ul className="flex flex-col gap-3">
        {items.map((item) => {
          const { transfer } = item;
          const percent = progressOf(item);
          const Icon = transfer.direction === "TO_DEVICE" ? ArrowUpFromLine : ArrowDownToLine;
          return (
            <li key={transfer.id} className="flex flex-col gap-1">
              <div className="flex items-center gap-2">
                <Icon aria-hidden className="h-4 w-4 shrink-0 text-neutral-500" />
                <span className="min-w-0 flex-1 truncate text-sm" title={transfer.fileName}>
                  {transfer.fileName}
                </span>
                {transfer.size !== null && (
                  <span className="text-xs text-neutral-500">{formatBytes(transfer.size)}</span>
                )}
                {transfer.direction === "FROM_DEVICE" &&
                  (transfer.state === "READY" || transfer.state === "DELIVERED") && (
                    <Button variant="quiet" onPress={() => onSave(transfer)}>
                      Save
                    </Button>
                  )}
                {isOpen(transfer.state) ? (
                  <Button variant="quiet" aria-label={`Cancel ${transfer.fileName}`} onPress={() => onCancel(transfer.id)}>
                    <X aria-hidden className="h-4 w-4" />
                  </Button>
                ) : (
                  <Button
                    variant="quiet"
                    aria-label={`Remove ${transfer.fileName} from the list`}
                    onPress={() => onDismiss(transfer.id)}
                  >
                    <X aria-hidden className="h-4 w-4" />
                  </Button>
                )}
              </div>
              {percent !== null && <ProgressBar aria-label={`${transfer.fileName} progress`} value={percent} />}
              <span className={item.error || transfer.state === "FAILED" ? "text-xs text-red-600" : "text-xs text-neutral-500"}>
                {describe(item)}
              </span>
            </li>
          );
        })}
      </ul>
    </Card>
  );
}
