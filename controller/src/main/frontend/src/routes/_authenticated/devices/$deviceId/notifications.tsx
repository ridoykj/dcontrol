import { createFileRoute } from "@tanstack/react-router";
import { NotificationsPage } from "@/features/notifications/pages/NotificationsPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/notifications")({
  component: function NotificationsRoute() {
    const { deviceId } = Route.useParams();
    return <NotificationsPage deviceId={deviceId} />;
  },
});
