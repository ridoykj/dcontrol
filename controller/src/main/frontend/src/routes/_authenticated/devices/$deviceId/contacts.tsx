import { createFileRoute } from "@tanstack/react-router";
import { useCallback } from "react";
import { ContactsPage } from "@/features/contacts/pages/ContactsPage";

type ContactsSearch = { q?: string; id?: number; key?: string };

export const Route = createFileRoute("/_authenticated/devices/$deviceId/contacts")({
  validateSearch: (search: Record<string, unknown>): ContactsSearch => {
    const result: ContactsSearch = {};
    if (typeof search.q === "string" && search.q.length > 0 && search.q.length <= 100) result.q = search.q;
    const id = Number(search.id);
    if (Number.isSafeInteger(id) && id >= 0 && typeof search.key === "string" && search.key.length > 0) {
      result.id = id;
      result.key = search.key.slice(0, 512);
    }
    return result;
  },
  component: function ContactsRoute() {
    const { deviceId } = Route.useParams();
    const { q, id, key } = Route.useSearch();
    const navigate = Route.useNavigate();
    const onQueryChange = useCallback(
      (query: string) => void navigate({ search: (prev) => ({ ...prev, q: query || undefined }), replace: true }),
      [navigate],
    );
    return (
      <ContactsPage
        deviceId={deviceId}
        query={q ?? ""}
        selected={id !== undefined && key ? { id, lookupKey: key } : null}
        onQueryChange={onQueryChange}
        onSelect={(contact) =>
          void navigate({ search: (prev) => ({ q: prev.q, id: contact?.id, key: contact?.lookupKey }) })
        }
        onMessage={(number) =>
          void navigate({ to: "/devices/$deviceId/messages", params: { deviceId }, search: { to: number } })
        }
      />
    );
  },
});
