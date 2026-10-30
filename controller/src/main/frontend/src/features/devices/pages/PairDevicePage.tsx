import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useRef } from "react";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { cancelPairing, createPairing, getPairing } from "../api/devicesApi";
import { PairingQr } from "../components/PairingQr";
import { pairingCompletedSchema } from "../schemas/deviceSchemas";

export function PairDevicePage() {
  const navigate = useNavigate();
  const { client, state } = useRealtime();
  const create = useMutation({ mutationFn: createPairing });
  const started = useRef(false);
  const pairing = create.data;

  useEffect(() => {
    if (!started.current) {
      started.current = true;
      create.mutate();
    }
  }, [create]);

  useEffect(() => {
    if (!pairing) return;
    return client.subscribe((message) => {
      if (message.type !== "pairing.completed") return;
      const completed = pairingCompletedSchema.safeParse(message.payload);
      if (completed.success && completed.data.pairingId === pairing.id) {
        void navigate({ to: "/devices/$deviceId", params: { deviceId: completed.data.deviceId } });
      }
    });
  }, [client, navigate, pairing]);

  // Fallback when the realtime connection is down: poll the pairing state.
  const polled = useQuery({
    queryKey: ["pairings", pairing?.id],
    queryFn: () => getPairing(pairing!.id),
    enabled: !!pairing && state !== "open",
    refetchInterval: 3000,
  });
  useEffect(() => {
    if (polled.data?.state === "CLAIMED" && polled.data.deviceId) {
      void navigate({ to: "/devices/$deviceId", params: { deviceId: polled.data.deviceId } });
    }
  }, [navigate, polled.data]);

  const cancel = useMutation({
    mutationFn: async () => {
      if (pairing) await cancelPairing(pairing.id).catch(() => undefined);
    },
    onSettled: () => navigate({ to: "/devices" }),
  });

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-6">
      <h1 className="text-2xl font-semibold">Pair a device</h1>
      <Card className="flex flex-col gap-5">
        <ol className="list-decimal space-y-1 pl-5 text-sm text-neutral-700 dark:text-neutral-300">
          <li>Open the dcontrol app on your phone.</li>
          <li>Tap “Pair with a computer” and allow camera access.</li>
          <li>Scan this code and confirm the account shown on the phone.</li>
        </ol>
        {create.isPending && <ProgressBar aria-label="Creating pairing code" isIndeterminate />}
        {create.isError && <Alert tone="error">{errorMessage(create.error)}</Alert>}
        {pairing?.qrPayload && (
          <>
            <PairingQr
              payload={pairing.qrPayload}
              expiresAt={pairing.expiresAt}
              onRegenerate={() => create.mutate()}
              isRegenerating={create.isPending}
            />
            <details className="text-sm">
              <summary className="cursor-pointer text-neutral-600 dark:text-neutral-400">Can't scan?</summary>
              <p className="mt-2 text-neutral-600 dark:text-neutral-400">
                On an emulator with a debug build, open this link on the device (for example with{" "}
                <code>adb shell 'am start -d "…"'</code>):
              </p>
              <code className="mt-2 block break-all rounded bg-neutral-100 p-2 text-xs dark:bg-neutral-800">
                {pairing.qrPayload}
              </code>
            </details>
          </>
        )}
        <Button variant="secondary" isPending={cancel.isPending} onPress={() => cancel.mutate()}>
          Cancel
        </Button>
      </Card>
    </div>
  );
}
