import { useQuery } from "@tanstack/react-query";
import { MessageSquareReply, RefreshCw, X } from "lucide-react";
import { useState } from "react";
import { Form, Label, TextArea, TextField } from "react-aria-components";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { Switch } from "@/shared/components/ui/shadcn/Switch";
import { formatRelativeTime } from "@/shared/lib/utils";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { AppIcon } from "../components/AppIcon";
import { useDesktopNotifications } from "../hooks/useDesktopNotifications";
import { useNotifications } from "../hooks/useNotifications";
import { replySchema, type MirroredNotification } from "../schemas/notificationSchemas";

function ReplyForm({ onSend, onDone }: { onSend: (text: string) => Promise<void>; onDone: () => void }) {
  const [text, setText] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  return (
    <Form
      validationBehavior="aria"
      className="flex flex-col gap-2"
      onSubmit={async (event) => {
        event.preventDefault();
        const parsed = replySchema.safeParse({ text });
        if (!parsed.success) {
          setProblem(parsed.error.issues[0]?.message ?? "Invalid reply");
          return;
        }
        setPending(true);
        setProblem(null);
        try {
          await onSend(parsed.data.text);
          onDone();
        } catch (error) {
          setProblem(relayErrorMessage(error));
        } finally {
          setPending(false);
        }
      }}
    >
      <TextField value={text} onChange={setText} isInvalid={problem !== null} className="flex flex-col gap-1">
        <Label className="text-xs text-neutral-500">Reply</Label>
        <TextArea
          autoFocus
          rows={2}
          className="rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-900"
        />
      </TextField>
      {problem && <span className="text-xs text-red-600">{problem}</span>}
      <div className="flex justify-end gap-2">
        <Button variant="secondary" onPress={onDone}>
          Cancel
        </Button>
        <Button type="submit" isPending={pending}>
          Send
        </Button>
      </div>
    </Form>
  );
}

function NotificationCard({
  deviceId,
  notification,
  onDismiss,
  onReply,
}: {
  deviceId: string;
  notification: MirroredNotification;
  onDismiss: () => Promise<void>;
  onReply: (text: string) => Promise<void>;
}) {
  const [replying, setReplying] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  return (
    <li>
      <Card className="flex flex-col gap-3 p-4">
        <div className="flex gap-3">
          <AppIcon deviceId={deviceId} hash={notification.iconHash} label={notification.appLabel} />
          <div className="min-w-0 flex-1">
            <div className="flex items-baseline gap-2 text-xs text-neutral-500">
              <span className="font-medium text-neutral-700 dark:text-neutral-300">{notification.appLabel}</span>
              {notification.subText && <span className="truncate">{notification.subText}</span>}
              <span className="ml-auto shrink-0">{formatRelativeTime(notification.postedAt)}</span>
            </div>
            {notification.title && <p className="truncate font-medium">{notification.title}</p>}
            {notification.text && (
              <p className="text-sm whitespace-pre-line break-words text-neutral-700 dark:text-neutral-300">
                {notification.text}
              </p>
            )}
          </div>
        </div>
        {problem && <span className="text-xs text-red-600">{problem}</span>}
        {replying ? (
          <ReplyForm onSend={onReply} onDone={() => setReplying(false)} />
        ) : (
          (notification.canReply || notification.clearable) && (
            <div className="flex justify-end gap-2">
              {notification.canReply && (
                <Button variant="secondary" onPress={() => setReplying(true)}>
                  <MessageSquareReply aria-hidden className="h-4 w-4" />
                  <span>Reply</span>
                </Button>
              )}
              {notification.clearable && (
                <Button
                  variant="quiet"
                  onPress={() => {
                    setProblem(null);
                    onDismiss().catch((error) => setProblem(relayErrorMessage(error)));
                  }}
                >
                  <X aria-hidden className="h-4 w-4" />
                  <span>Dismiss</span>
                </Button>
              )}
            </div>
          )
        )}
      </Card>
    </li>
  );
}

export function NotificationsPage({ deviceId }: { deviceId: string }) {
  const { client } = useRealtime();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const { status, notifications, reload, dismiss, reply } = useNotifications(client, deviceId, online);
  const desktop = useDesktopNotifications(deviceId, device.data?.name ?? "Phone");

  if (!device.isSuccess) return null;

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        {desktop.supported ? (
          <Switch isSelected={desktop.enabled} onChange={(on) => void desktop.toggle(on)}>
            Show as desktop notifications while this tab is in the background
          </Switch>
        ) : (
          <span />
        )}
        <Button variant="secondary" isDisabled={!online} onPress={() => void reload()}>
          <RefreshCw aria-hidden className="h-4 w-4" />
          <span>Reload</span>
        </Button>
      </div>
      {!online && <Alert>The phone is offline. Notifications appear here live while it is connected.</Alert>}
      {online && status === "loading" && <ProgressBar aria-label="Loading notifications" isIndeterminate />}
      {online && status === "error" && <Alert tone="error">Couldn't read the phone's notifications. Try again.</Alert>}
      {status === "accessMissing" && (
        <Alert>
          The phone hasn't allowed notification access. Open dcontrol on the phone and tap <strong>Grant</strong> on
          the Notification access card.
        </Alert>
      )}
      {online && status === "ready" && notifications.length === 0 && (
        <p className="text-sm text-neutral-500">No notifications on the phone right now.</p>
      )}
      <ul className="flex flex-col gap-3" aria-label="Notifications">
        {notifications.map((notification) => (
          <NotificationCard
            key={notification.key}
            deviceId={deviceId}
            notification={notification}
            onDismiss={() => dismiss(notification)}
            onReply={(text) => reply(notification, text)}
          />
        ))}
      </ul>
    </div>
  );
}
