import { createFileRoute } from "@tanstack/react-router";
import { FindPage } from "@/features/findphone/pages/FindPage";

export const Route = createFileRoute("/_authenticated/devices/$deviceId/find")({
  component: function FindRoute() {
    const { deviceId } = Route.useParams();
    return <FindPage deviceId={deviceId} />;
  },
});
