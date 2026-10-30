import { useQuery, useQueryClient } from "@tanstack/react-query";
import { RefreshCw, Trash2 } from "lucide-react";
import { useState } from "react";
import type { Selection } from "react-aria-components";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { PlaceCallDialog } from "@/features/devices/components/PlaceCallDialog";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { AlertDialog } from "@/shared/components/ui/shadcn/AlertDialog";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Select, SelectItem } from "@/shared/components/ui/shadcn/Select";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import { useRelayedPages } from "@/shared/ws/useRelayedPages";
import { z } from "zod";
import { CallsTable } from "../components/CallsTable";
import {
  CALL_TYPES,
  callsDeletedSchema,
  callsPageSchema,
  callTypeLabels,
  type CallEntry,
  type CallFilter,
} from "../schemas/callsSchemas";

const callKeys = {
  all: (deviceId: string) => ["phone", deviceId, "calls"] as const,
  list: (deviceId: string, type: CallFilter | null) => ["phone", deviceId, "calls", type ?? "ALL"] as const,
};
const changedSchema = z.object({ deviceId: z.string() });
const ALL = "ALL";

type CallsPageProps = {
  deviceId: string;
  type: CallFilter | null;
  onTypeChange: (type: CallFilter | null) => void;
  onMessage: (number: string) => void;
};

/** The phone's call log, read live: filter, delete entries, call back or text. */
export function CallsPage({ deviceId, type, onTypeChange, onMessage }: CallsPageProps) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const [selected, setSelected] = useState<Selection>(new Set());
  const [deleting, setDeleting] = useState<number[] | null>(null);
  const [calling, setCalling] = useState<(CallEntry & { number: string }) | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const pages = useRelayedPages({
    queryKey: callKeys.list(deviceId, type),
    type: "calls.list",
    payload: { deviceId, type },
    schema: callsPageSchema,
    enabled: online,
  });
  const calls = pages.data?.pages.flatMap((page) => page.items) ?? [];

  useRealtimeEvent("calls.changed", changedSchema, (event) => {
    if (event.deviceId === deviceId) void queryClient.invalidateQueries({ queryKey: callKeys.all(deviceId) });
  });

  if (!device.isSuccess) return null;
  if (!online) return <Alert>The phone is offline. The call log is read live from the phone while it is connected.</Alert>;
  const permission = missingPermission(pages.error);
  if (permission) return <PermissionRequired permission={permission} />;

  const selectedIds =
    selected === "all" ? calls.map((call) => call.id) : [...selected].map(Number).filter((id) => calls.some((call) => call.id === id));

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-3">
        <Select
          label="Show"
          selectedKey={type ?? ALL}
          onSelectionChange={(key) => {
            setSelected(new Set());
            onTypeChange(key === ALL ? null : (key as CallFilter));
          }}
        >
          <SelectItem id={ALL}>All calls</SelectItem>
          {CALL_TYPES.filter((candidate) => candidate !== "OTHER").map((candidate) => (
            <SelectItem key={candidate} id={candidate}>
              {callTypeLabels[candidate]}
            </SelectItem>
          ))}
        </Select>
        <Button variant="secondary" onPress={() => void pages.refetch()}>
          <RefreshCw aria-hidden className="h-4 w-4" />
          <span>Reload</span>
        </Button>
        {selectedIds.length > 0 && (
          <Button variant="destructive" className="ml-auto" onPress={() => setDeleting(selectedIds)}>
            <Trash2 aria-hidden className="h-4 w-4" />
            <span>Delete {selectedIds.length}</span>
          </Button>
        )}
      </div>

      {failure && <Alert tone="error">{failure}</Alert>}
      {pages.isPending && <ProgressBar aria-label="Loading call log" isIndeterminate />}
      {pages.isError && <Alert tone="error">{relayErrorMessage(pages.error)}</Alert>}
      {pages.isSuccess && (
        <CallsTable
          calls={calls}
          selected={selected}
          onSelectionChange={setSelected}
          onCall={setCalling}
          onMessage={onMessage}
        />
      )}
      {pages.hasNextPage && (
        <Button
          variant="secondary"
          className="self-center"
          isPending={pages.isFetchingNextPage}
          onPress={() => void pages.fetchNextPage()}
        >
          Load more
        </Button>
      )}

      <Modal isOpen={deleting !== null} onOpenChange={(open) => !open && setDeleting(null)}>
        {deleting && (
          <AlertDialog
            title={deleting.length === 1 ? "Delete this call?" : `Delete ${deleting.length} calls?`}
            variant="destructive"
            actionLabel="Delete"
            onAction={() => {
              setFailure(null);
              client
                .request("calls.delete", { deviceId, ids: deleting.slice(0, 100) })
                .then((result) => {
                  callsDeletedSchema.parse(result.payload);
                  setSelected(new Set());
                  void queryClient.invalidateQueries({ queryKey: callKeys.all(deviceId) });
                })
                .catch((error) => {
                  const missing = missingPermission(error);
                  setFailure(missing ? "The phone hasn't allowed deleting call log entries." : relayErrorMessage(error));
                });
            }}
          >
            The entries are deleted from the phone's call log. This can't be undone.
          </AlertDialog>
        )}
      </Modal>
      <Modal isOpen={calling !== null} onOpenChange={(open) => !open && setCalling(null)}>
        {calling && <PlaceCallDialog deviceId={deviceId} number={calling.number} name={calling.name} />}
      </Modal>
    </div>
  );
}
