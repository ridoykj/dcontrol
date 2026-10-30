import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BellOff, BellRing, ExternalLink, Lock, MapPin, RefreshCw } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { FieldError, Form, Label, TextArea, TextField } from "react-aria-components";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { missingPermission, PermissionRequired } from "@/features/devices/components/PermissionRequired";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { AlertDialog } from "@/shared/components/ui/shadcn/AlertDialog";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { clearLocation, getLocation, locationKeys, refreshLocation } from "../api/locationApi";
import {
  ago,
  formatAccuracy,
  formatCoordinates,
  type LockForm,
  lockFormSchema,
  mapLinks,
  providerLabels,
} from "../schemas/findSchemas";

type Status = { tone: "success" | "error"; message: string } | null;

function RingCard({ deviceId, online }: { deviceId: string; online: boolean }) {
  const { client } = useRealtime();
  const [status, setStatus] = useState<Status>(null);
  const ring = useMutation({
    mutationFn: (action: "START" | "STOP") => client.request("find.ring", { deviceId, action }),
    onSuccess: (_, action) =>
      setStatus({
        tone: "success",
        message: action === "START" ? "The phone is ringing. It stops after 5 minutes." : "Ringing stopped.",
      }),
    onError: (error) => setStatus({ tone: "error", message: relayErrorMessage(error) }),
  });
  return (
    <Card className="flex flex-col gap-3">
      <h2 className="font-medium">Ring</h2>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        Plays the alarm sound at full volume, even in silent mode, until you stop it here or on the phone.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button isDisabled={!online} isPending={ring.isPending && ring.variables === "START"} onPress={() => ring.mutate("START")}>
          <BellRing aria-hidden className="h-4 w-4" />
          <span>Ring</span>
        </Button>
        <Button
          variant="secondary"
          isDisabled={!online}
          isPending={ring.isPending && ring.variables === "STOP"}
          onPress={() => ring.mutate("STOP")}
        >
          <BellOff aria-hidden className="h-4 w-4" />
          <span>Stop ringing</span>
        </Button>
      </div>
      {status && <Alert tone={status.tone}>{status.message}</Alert>}
    </Card>
  );
}

function LocationCard({ deviceId, online }: { deviceId: string; online: boolean }) {
  const queryClient = useQueryClient();
  const key = locationKeys.detail(deviceId);
  const location = useQuery({ queryKey: key, queryFn: () => getLocation(deviceId) });
  const locate = useMutation({
    mutationFn: () => refreshLocation(deviceId),
    onSuccess: (fix) => queryClient.setQueryData(key, fix),
  });
  const clear = useMutation({
    mutationFn: () => clearLocation(deviceId),
    onSuccess: () => queryClient.setQueryData(key, null),
  });
  const [confirmClear, setConfirmClear] = useState(false);
  const permission = missingPermission(locate.error);
  const fix = location.data;
  const links = fix ? mapLinks(fix.latitude, fix.longitude) : null;

  return (
    <Card className="flex flex-col gap-3">
      <h2 className="font-medium">Location</h2>
      {location.isError && <Alert tone="error">{errorMessage(location.error)}</Alert>}
      {location.isSuccess && !fix && <p className="text-sm text-neutral-500">No location stored yet.</p>}
      {fix && links && (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-neutral-500">Position</dt>
          <dd className="font-mono">{formatCoordinates(fix.latitude, fix.longitude)}</dd>
          <dt className="text-neutral-500">Accuracy</dt>
          <dd>{formatAccuracy(fix.accuracyM)}</dd>
          <dt className="text-neutral-500">Fixed</dt>
          <dd>
            {ago(fix.fixedAt)} · {providerLabels[fix.provider]}
            {fix.stale && (
              <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-xs text-amber-800 dark:bg-amber-950 dark:text-amber-200">
                last known, not fresh
              </span>
            )}
          </dd>
          <dt className="text-neutral-500">Open in</dt>
          <dd className="flex flex-wrap gap-3">
            <a className="inline-flex items-center gap-1 text-blue-600 hover:underline" href={links.openStreetMap} target="_blank" rel="noopener noreferrer">
              OpenStreetMap <ExternalLink aria-hidden className="h-3 w-3" />
            </a>
            <a className="inline-flex items-center gap-1 text-blue-600 hover:underline" href={links.googleMaps} target="_blank" rel="noopener noreferrer">
              Google Maps <ExternalLink aria-hidden className="h-3 w-3" />
            </a>
          </dd>
        </dl>
      )}
      <div className="flex flex-wrap gap-2">
        <Button isDisabled={!online} isPending={locate.isPending} onPress={() => locate.mutate()}>
          {fix ? <RefreshCw aria-hidden className="h-4 w-4" /> : <MapPin aria-hidden className="h-4 w-4" />}
          <span>Locate now</span>
        </Button>
        {fix && (
          <Button variant="quiet" isPending={clear.isPending} onPress={() => setConfirmClear(true)}>
            Forget location
          </Button>
        )}
      </div>
      {!online && <p className="text-xs text-neutral-500">The phone is offline; showing the last stored location.</p>}
      {permission && <PermissionRequired permission={permission} />}
      {locate.isError && !permission && <Alert tone="error">{errorMessage(locate.error)}</Alert>}
      {clear.isError && <Alert tone="error">{errorMessage(clear.error)}</Alert>}
      <Modal isOpen={confirmClear} onOpenChange={setConfirmClear}>
        <AlertDialog title="Forget the stored location?" variant="destructive" actionLabel="Forget" onAction={() => clear.mutate()}>
          The last known position is deleted from the server. Locate the phone again to store a new one.
        </AlertDialog>
      </Modal>
    </Card>
  );
}

function LockCard({ deviceId, online }: { deviceId: string; online: boolean }) {
  const { client } = useRealtime();
  const form = useForm<LockForm>({
    resolver: zodResolver(lockFormSchema),
    defaultValues: { message: "This phone is lost. Please call the owner.", contact: "" },
  });
  const [pending, setPending] = useState<LockForm | null>(null);
  const [status, setStatus] = useState<Status>(null);
  const [lockError, setLockError] = useState<unknown>(null);
  const lock = useMutation({
    mutationFn: (values: LockForm) =>
      client.request("find.lock", { deviceId, message: values.message, contact: values.contact || null }),
    onMutate: () => {
      setStatus(null);
      setLockError(null);
    },
    onSuccess: () => setStatus({ tone: "success", message: "The phone is locked and shows your message." }),
    onError: (error) => {
      setLockError(error);
      setStatus({ tone: "error", message: relayErrorMessage(error) });
    },
  });
  const permission = missingPermission(lockError);

  return (
    <Card className="flex flex-col gap-3">
      <h2 className="font-medium">Lock with a message</h2>
      <p className="text-sm text-neutral-600 dark:text-neutral-400">
        Locks the screen and shows your message on the lock screen. Needs the Remote control service on the phone.
      </p>
      <Form validationBehavior="aria" className="flex flex-col gap-3" onSubmit={form.handleSubmit(setPending)}>
        <Controller
          control={form.control}
          name="message"
          render={({ field, fieldState }) => (
            <TextField
              value={field.value}
              onChange={field.onChange}
              onBlur={field.onBlur}
              isInvalid={fieldState.invalid}
              className="flex flex-col gap-1"
            >
              <Label className="text-sm">Message</Label>
              <TextArea
                rows={3}
                className="rounded-lg border border-neutral-300 bg-white px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-900"
              />
              <FieldError className="text-sm text-red-600">{fieldState.error?.message}</FieldError>
            </TextField>
          )}
        />
        <FormTextField control={form.control} name="contact" label="Contact number (optional)" />
        <Button type="submit" className="self-start" variant="destructive" isDisabled={!online} isPending={lock.isPending}>
          <Lock aria-hidden className="h-4 w-4" />
          <span>Lock phone</span>
        </Button>
      </Form>
      {permission ? <PermissionRequired permission={permission} /> : status && <Alert tone={status.tone}>{status.message}</Alert>}
      <Modal isOpen={pending !== null} onOpenChange={(open) => !open && setPending(null)}>
        {pending && (
          <AlertDialog title="Lock the phone?" variant="destructive" actionLabel="Lock" onAction={() => lock.mutate(pending)}>
            The screen locks right away. Whoever has the phone needs its PIN, pattern or fingerprint to unlock it.
          </AlertDialog>
        )}
      </Modal>
    </Card>
  );
}

/** Ring, locate and lock a lost phone. */
export function FindPage({ deviceId }: { deviceId: string }) {
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  if (!device.isSuccess) return null;
  const online = device.data.status.online;
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      {!online && (
        <Alert className="lg:col-span-2">The phone is offline. Ringing, locating and locking need it to be connected.</Alert>
      )}
      <LocationCard deviceId={deviceId} online={online} />
      <div className="flex flex-col gap-4">
        <RingCard deviceId={deviceId} online={online} />
        <LockCard deviceId={deviceId} online={online} />
      </div>
    </div>
  );
}
