import { createFileRoute } from "@tanstack/react-router";
import { MicPage } from "@/features/mic/pages/MicPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/mic")({
  component: function MicRoute() {
    const { deviceId } = Route.useParams();
    return <MicPage deviceId={deviceId} />;
  },
});
