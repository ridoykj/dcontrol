import { createFileRoute } from "@tanstack/react-router";
import { MessagesPage } from "@/features/messages/pages/MessagesPage";

type MessagesSearch = { thread?: number; to?: string };

export const Route = createFileRoute("/_authenticated/devices/$deviceId/messages")({
  validateSearch: (search: Record<string, unknown>): MessagesSearch => {
    const thread = Number(search.thread);
    if (search.thread !== undefined && Number.isSafeInteger(thread) && thread >= 0) return { thread };
    if (typeof search.to === "string" && search.to.length <= 40) return { to: search.to };
    return {};
  },
  component: function MessagesRoute() {
    const { deviceId } = Route.useParams();
    const { thread, to } = Route.useSearch();
    const navigate = Route.useNavigate();
    return (
      <MessagesPage
        deviceId={deviceId}
        threadId={thread ?? null}
        to={to ?? null}
        onOpenThread={(threadId) => void navigate({ search: { thread: threadId } })}
        onNewMessage={(number) => void navigate({ search: { to: number ?? "" } })}
      />
    );
  },
});
