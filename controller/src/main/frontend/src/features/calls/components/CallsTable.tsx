import { MessageSquare, Phone, PhoneIncoming, PhoneMissed, PhoneOff, PhoneOutgoing, Voicemail } from "lucide-react";
import type { Selection } from "react-aria-components";
import { dialable } from "@/features/devices/components/PlaceCallDialog";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Cell, Column, Row, Table, TableBody, TableHeader } from "@/shared/components/ui/shadcn/Table";
import { cn } from "@/shared/lib/utils";
import { callParty, callTypeLabels, formatDuration, type CallEntry, type CallType } from "../schemas/callsSchemas";

const icons: Record<CallType, typeof Phone> = {
  INCOMING: PhoneIncoming,
  OUTGOING: PhoneOutgoing,
  MISSED: PhoneMissed,
  VOICEMAIL: Voicemail,
  REJECTED: PhoneOff,
  BLOCKED: PhoneOff,
  ANSWERED_EXTERNALLY: PhoneIncoming,
  OTHER: Phone,
};

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

type CallsTableProps = {
  calls: CallEntry[];
  selected: Selection;
  onSelectionChange: (selection: Selection) => void;
  onCall: (call: CallEntry & { number: string }) => void;
  onMessage: (number: string) => void;
};

/** The phone's call log, newest first as the phone sends it. */
export function CallsTable({ calls, selected, onSelectionChange, onCall, onMessage }: CallsTableProps) {
  return (
    <Table
      aria-label="Call log"
      className="max-h-[60vh]"
      selectionMode="multiple"
      selectionBehavior="toggle"
      selectedKeys={selected}
      onSelectionChange={onSelectionChange}
    >
      <TableHeader>
        <Column id="party" isRowHeader minWidth={200}>
          Contact
        </Column>
        <Column id="type" width={160}>
          Type
        </Column>
        <Column id="date" width={190}>
          Date
        </Column>
        <Column id="duration" width={90}>
          Duration
        </Column>
        <Column id="actions" width={100}>
          <span className="sr-only">Actions</span>
        </Column>
      </TableHeader>
      <TableBody items={calls} renderEmptyState={() => <p className="p-4 text-sm text-neutral-500">No calls.</p>}>
        {(call) => {
          const Icon = icons[call.type];
          const number = call.presentation === "ALLOWED" && call.number && dialable(call.number) ? call.number : null;
          const missed = call.type === "MISSED" || call.type === "REJECTED";
          return (
            <Row id={call.id} textValue={callParty(call)}>
              <Cell>
                <span className={cn("block truncate", missed && "text-red-600", call.isNew && "font-semibold")}>
                  {callParty(call)}
                </span>
                {call.name && call.number && <span className="block truncate text-xs text-neutral-500 tabular-nums">{call.number}</span>}
              </Cell>
              <Cell>
                <span className="flex items-center gap-2">
                  <Icon aria-hidden className={cn("h-4 w-4", missed ? "text-red-600" : "text-neutral-500")} />
                  {callTypeLabels[call.type]}
                  {call.simLabel && <span className="text-xs text-neutral-500">· {call.simLabel}</span>}
                </span>
              </Cell>
              <Cell>{dateFormat.format(new Date(call.date))}</Cell>
              <Cell>
                <span className="tabular-nums">{formatDuration(call.durationSec)}</span>
              </Cell>
              <Cell>
                {number && (
                  <span className="flex gap-1">
                    <Button variant="quiet" aria-label={`Call ${callParty(call)}`} onPress={() => onCall({ ...call, number })}>
                      <Phone aria-hidden className="h-4 w-4" />
                    </Button>
                    <Button variant="quiet" aria-label={`Message ${callParty(call)}`} onPress={() => onMessage(number)}>
                      <MessageSquare aria-hidden className="h-4 w-4" />
                    </Button>
                  </span>
                )}
              </Cell>
            </Row>
          );
        }}
      </TableBody>
    </Table>
  );
}
