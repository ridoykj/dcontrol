import { createFileRoute } from "@tanstack/react-router";
import { CallsPage } from "@/features/calls/pages/CallsPage";
import { CALL_TYPES, type CallFilter } from "@/features/calls/schemas/callsSchemas";

type CallsSearch = { type?: CallFilter };

export const Route = createFileRoute("/_authenticated/devices/$deviceId/calls")({
  validateSearch: (search: Record<string, unknown>): CallsSearch => {
    const type = CALL_TYPES.find((candidate) => candidate !== "OTHER" && candidate === search.type);
    return type && type !== "OTHER" ? { type } : {};
  },
  component: function CallsRoute() {
    const { deviceId } = Route.useParams();
    const { type } = Route.useSearch();
    const navigate = Route.useNavigate();
    return (
      <CallsPage
        deviceId={deviceId}
        type={type ?? null}
        onTypeChange={(next) => void navigate({ search: next ? { type: next } : {} })}
        onMessage={(number) =>
          void navigate({ to: "/devices/$deviceId/messages", params: { deviceId }, search: { to: number } })
        }
      />
    );
  },
});
