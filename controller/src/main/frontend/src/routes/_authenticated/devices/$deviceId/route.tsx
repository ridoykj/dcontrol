import { createFileRoute } from "@tanstack/react-router";
import { DeviceLayout } from "@/features/devices/pages/DeviceLayout";

export const Route = createFileRoute("/_authenticated/devices/$deviceId")({
  component: function DeviceLayoutRoute() {
    const { deviceId } = Route.useParams();
    return <DeviceLayout key={deviceId} deviceId={deviceId} />;
  },
});
