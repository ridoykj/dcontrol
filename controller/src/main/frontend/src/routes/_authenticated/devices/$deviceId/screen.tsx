import { createFileRoute } from "@tanstack/react-router";
import { ScreenPage } from "@/features/screen/pages/ScreenPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/screen")({
  component: function ScreenRoute() {
    const { deviceId } = Route.useParams();
    return <ScreenPage deviceId={deviceId} />;
  },
});
