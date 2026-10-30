import { createFileRoute } from "@tanstack/react-router";
import { ClipboardPage } from "@/features/clipboard/pages/ClipboardPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/clipboard")({
  component: function ClipboardRoute() {
    const { deviceId } = Route.useParams();
    return <ClipboardPage deviceId={deviceId} />;
  },
});
