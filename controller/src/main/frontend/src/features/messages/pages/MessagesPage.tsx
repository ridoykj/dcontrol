import { useQuery, useQueryClient } from "@tanstack/react-query";
import { PenSquare, RefreshCw } from "lucide-react";
import { useCallback, useEffect, useMemo, useReducer, useState } from "react";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { useSims } from "@/features/devices/api/simsApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { dialable } from "@/features/devices/components/PlaceCallDialog";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { TextField } from "@/shared/components/ui/shadcn/TextField";
import { cn, formatRelativeTime } from "@/shared/lib/utils";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { useRealtimeEvent } from "@/shared/ws/useRealtimeEvent";
import { useRelayedPages } from "@/shared/ws/useRelayedPages";
import { Composer } from "../components/Composer";
import { ListedBubble, OutgoingBubble } from "../components/MessageBubble";
import { outgoingReducer, unlistedOutgoing, type Outgoing } from "../lib/outgoingState";
import {
  deviceHintSchema,
  messagesPageSchema,
  sendResultSchema,
  smsReceivedSchema,
  smsStatusSchema,
  threadsPageSchema,
  type SmsThread,
} from "../schemas/smsSchemas";

export const smsKeys = {
  all: (deviceId: string) => ["phone", deviceId, "sms"] as const,
  threads: (deviceId: string) => ["phone", deviceId, "sms", "threads"] as const,
  messages: (deviceId: string, threadId: number) => ["phone", deviceId, "sms", "messages", threadId] as const,
};

type MessagesPageProps = {
  deviceId: string;
  threadId: number | null;
  /** Number for a new conversation (e.g. from a contact). */
  to: string | null;
  onOpenThread: (threadId: number) => void;
  onNewMessage: (to: string | null) => void;
};

function threadTitle(thread: SmsThread): string {
  return thread.participants.map((participant) => participant.name || participant.address).join(", ");
}

const lastDigits = (value: string) => value.replace(/\D/g, "").slice(-9);

function Conversation({
  deviceId,
  thread,
  outgoing,
}: {
  deviceId: string;
  thread: SmsThread;
  outgoing: Outgoing[];
}) {
  const pages = useRelayedPages({
    queryKey: smsKeys.messages(deviceId, thread.threadId),
    type: "sms.messages",
    payload: { deviceId, threadId: thread.threadId },
    schema: messagesPageSchema,
    enabled: true,
  });
  const listed = pages.data?.pages.flatMap((page) => page.items) ?? [];
  const oldestFirst = [...listed].reverse();
  const addresses = thread.participants.map((participant) => lastDigits(participant.address));
  const pending = unlistedOutgoing(
    outgoing.filter((item) => addresses.includes(lastDigits(item.to))),
    listed,
  );

  if (pages.isPending) return <ProgressBar aria-label="Loading messages" isIndeterminate />;
  if (pages.isError) return <Alert tone="error">{relayErrorMessage(pages.error)}</Alert>;
  return (
    <div className="flex flex-col gap-2">
      {pages.hasNextPage && (
        <Button
          variant="quiet"
          className="self-center"
          isPending={pages.isFetchingNextPage}
          onPress={() => void pages.fetchNextPage()}
        >
          Load older messages
        </Button>
      )}
      <ul aria-label={`Messages with ${threadTitle(thread)}`} className="flex max-h-[55vh] flex-col gap-2 overflow-y-auto">
        {oldestFirst.map((message) => (
          <ListedBubble key={`${message.kind}${message.id}`} message={message} />
        ))}
        {pending.map((item) => (
          <OutgoingBubble key={item.clientRef} item={item} />
        ))}
      </ul>
    </div>
  );
}

/** SMS threads and conversations read live from the phone; sending goes through the phone's SIM. */
export function MessagesPage({ deviceId, threadId, to, onOpenThread, onNewMessage }: MessagesPageProps) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const sims = useSims(deviceId, online);
  const [outgoing, dispatch] = useReducer(outgoingReducer, []);
  const [recipient, setRecipient] = useState(to ?? "");
  useEffect(() => setRecipient(to ?? ""), [to]);

  const threads = useRelayedPages({
    queryKey: smsKeys.threads(deviceId),
    type: "sms.threads",
    payload: { deviceId },
    schema: threadsPageSchema,
    enabled: online,
  });
  const threadList = useMemo(() => threads.data?.pages.flatMap((page) => page.items) ?? [], [threads.data]);
  const thread = threadList.find((candidate) => candidate.threadId === threadId) ?? null;

  const refresh = useCallback(
    () => void queryClient.invalidateQueries({ queryKey: smsKeys.all(deviceId) }),
    [queryClient, deviceId],
  );
  useRealtimeEvent("sms.received", smsReceivedSchema, (event) => {
    if (event.deviceId === deviceId) refresh();
  });
  useRealtimeEvent("sms.changed", deviceHintSchema, (event) => {
    if (event.deviceId === deviceId) refresh();
  });
  useRealtimeEvent("sms.status", smsStatusSchema, (event) => {
    if (event.deviceId === deviceId) dispatch({ type: "status", ...event });
  });

  const send = (number: string, text: string, simId: number | null) => {
    const clientRef = crypto.randomUUID();
    dispatch({ type: "sending", clientRef, to: number, text, at: Date.now() });
    client
      .request("sms.send", { deviceId, to: number, text, simId, clientRef })
      .then((result) => {
        sendResultSchema.parse(result.payload);
        dispatch({ type: "queued", clientRef });
      })
      .catch((error) => dispatch({ type: "rejected", clientRef, error: relayErrorMessage(error) }));
  };

  if (!device.isSuccess) return null;
  if (!online) return <Alert>The phone is offline. Messages are read live from the phone while it is connected.</Alert>;
  const permission = missingPermission(threads.error);
  if (permission) return <PermissionRequired permission={permission} />;

  const composing = thread === null && to !== null;
  const newConversation = composing ? unlistedOutgoing(outgoing.filter((item) => lastDigits(item.to) === lastDigits(recipient)), []) : [];

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,22rem)_minmax(0,1fr)]">
      <div className="flex flex-col gap-3">
        <div className="flex gap-2">
          <Button onPress={() => onNewMessage("")}>
            <PenSquare aria-hidden className="h-4 w-4" />
            <span>New message</span>
          </Button>
          <Button variant="secondary" aria-label="Reload messages" onPress={refresh}>
            <RefreshCw aria-hidden className="h-4 w-4" />
          </Button>
        </div>
        {threads.isPending && <ProgressBar aria-label="Loading conversations" isIndeterminate />}
        {threads.isError && <Alert tone="error">{relayErrorMessage(threads.error)}</Alert>}
        {threads.isSuccess && threadList.length === 0 && <p className="text-sm text-neutral-500">No messages on the phone.</p>}
        <ul aria-label="Conversations" className="flex flex-col">
          {threadList.map((candidate) => {
            const active = candidate.threadId === threadId;
            return (
              <li key={candidate.threadId}>
                <button
                  type="button"
                  aria-current={active ? "true" : undefined}
                  onClick={() => onOpenThread(candidate.threadId)}
                  className={cn(
                    "flex w-full flex-col rounded-lg px-2 py-2 text-left hover:bg-neutral-100 dark:hover:bg-neutral-800",
                    active && "bg-blue-50 dark:bg-blue-950",
                  )}
                >
                  <span className="flex items-baseline gap-2">
                    <span className={cn("truncate", candidate.unreadCount > 0 ? "font-semibold" : "font-medium")}>
                      {threadTitle(candidate)}
                    </span>
                    {candidate.unreadCount > 0 && (
                      <span className="rounded-full bg-blue-600 px-1.5 text-[11px] text-white">{candidate.unreadCount}</span>
                    )}
                    <span className="ml-auto shrink-0 text-xs text-neutral-500">{formatRelativeTime(candidate.date)}</span>
                  </span>
                  <span className="truncate text-sm text-neutral-500">
                    {candidate.snippet ?? (candidate.hasMms ? "MMS" : "")}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
        {threads.hasNextPage && (
          <Button variant="secondary" isPending={threads.isFetchingNextPage} onPress={() => void threads.fetchNextPage()}>
            Load more
          </Button>
        )}
      </div>

      <Card className="flex flex-col gap-4">
        {thread && (
          <>
            <h2 className="truncate text-lg font-semibold">{threadTitle(thread)}</h2>
            <Conversation deviceId={deviceId} thread={thread} outgoing={outgoing} />
            {thread.participants.length === 1 && thread.participants[0] && dialable(thread.participants[0].address) ? (
              <Composer
                sims={sims.data?.sims ?? []}
                onSend={(text, simId) => send(thread.participants[0]!.address, text, simId)}
              />
            ) : (
              <p className="text-sm text-neutral-500">
                Replies are only possible to a single phone number. Group threads and sender names are read-only.
              </p>
            )}
          </>
        )}
        {composing && (
          <>
            <h2 className="text-lg font-semibold">New message</h2>
            <TextField
              label="To"
              type="tel"
              value={recipient}
              onChange={setRecipient}
              isInvalid={recipient !== "" && !dialable(recipient)}
              errorMessage="Enter a phone number (digits, optional +)"
              autoFocus
            />
            <ul aria-label="Sent messages" className="flex flex-col gap-2">
              {newConversation.map((item) => (
                <OutgoingBubble key={item.clientRef} item={item} />
              ))}
            </ul>
            <Composer
              sims={sims.data?.sims ?? []}
              isDisabled={!dialable(recipient)}
              onSend={(text, simId) => send(recipient, text, simId)}
            />
          </>
        )}
        {!thread && !composing && (
          <p className="text-sm text-neutral-500">
            {threadId !== null && threads.isSuccess ? "This conversation isn't in the loaded list." : "Select a conversation."}
          </p>
        )}
      </Card>
    </div>
  );
}
