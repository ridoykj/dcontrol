import { createFileRoute } from "@tanstack/react-router";
import { PairDevicePage } from "@/features/devices/pages/PairDevicePage";

export const Route = createFileRoute("/_authenticated/devices/pair")({
  component: PairDevicePage,
});
