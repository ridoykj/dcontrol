import { createFileRoute } from "@tanstack/react-router";
import { CameraPage } from "@/features/camera/pages/CameraPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/camera")({
  component: function CameraRoute() {
    const { deviceId } = Route.useParams();
    return <CameraPage deviceId={deviceId} />;
  },
});
