import { createFileRoute } from "@tanstack/react-router";
import { DeviceOverviewPage } from "@/features/devices/pages/DeviceOverviewPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/")({
  component: function DeviceOverviewRoute() {
    const { deviceId } = Route.useParams();
    return <DeviceOverviewPage deviceId={deviceId} />;
  },
});
