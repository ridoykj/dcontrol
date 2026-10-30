import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect } from "react";
import { DialogTrigger } from "react-aria-components";
import { useForm } from "react-hook-form";
import { errorMessage } from "@/shared/api/problem";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { applyServerErrors } from "@/shared/components/form/applyServerErrors";
import { FormTextField } from "@/shared/components/form/FormTextField";
import { AlertDialog } from "@/shared/components/ui/shadcn/AlertDialog";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Form } from "@/shared/components/ui/shadcn/Form";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { formatRelativeTime } from "@/shared/lib/utils";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { deleteDevice, deviceKeys, getDevice, renameDevice } from "../api/devicesApi";
import { DeviceInfoCards } from "../components/DeviceInfoCards";
import { relayErrorMessage } from "../components/relayErrorMessage";
import { pingResultSchema, renameSchema, type Device, type RenameValues } from "../schemas/deviceSchemas";

function RenameForm({ device }: { device: Device }) {
  const queryClient = useQueryClient();
  const form = useForm<RenameValues>({ resolver: zodResolver(renameSchema), defaultValues: { name: device.name } });
  const rename = useMutation({
    mutationFn: (values: RenameValues) => renameDevice(device.id, values.name),
    onSuccess: (updated) => {
      queryClient.setQueryData(deviceKeys.detail(device.id), updated);
      void queryClient.invalidateQueries({ queryKey: deviceKeys.lists() });
      form.reset({ name: updated.name });
    },
    onError: (error) => applyServerErrors(error, form.setError, ["name"]),
  });

  useEffect(() => {
    if (!form.formState.isDirty) form.reset({ name: device.name });
  }, [device.name, form]);

  return (
    <Form
      validationBehavior="aria"
      className="flex-row items-start gap-2"
      onSubmit={form.handleSubmit(async (values) => {
        await rename.mutateAsync(values).catch(() => undefined);
      })}
    >
      <FormTextField control={form.control} name="name" label="Name" className="flex-1" />
      <Button type="submit" variant="secondary" className="mt-6" isPending={rename.isPending}>
        Save
      </Button>
    </Form>
  );
}

function PingButton({ device }: { device: Device }) {
  const { client } = useRealtime();
  const ping = useMutation({
    mutationFn: async () => {
      const result = await client.request("device.ping", { deviceId: device.id });
      return pingResultSchema.parse(result.payload);
    },
  });

  return (
    <div className="flex items-center gap-3">
      <Button variant="secondary" isPending={ping.isPending} onPress={() => ping.mutate()}>
        Ping
      </Button>
      {ping.isSuccess && <span className="text-sm">Answered in {ping.data.rttMs} ms</span>}
      {ping.isError && <span className="text-sm text-red-600">{relayErrorMessage(ping.error)}</span>}
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-sm">{value}</dd>
    </div>
  );
}

export function DeviceOverviewPage({ deviceId }: { deviceId: string }) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const unpair = useMutation({
    mutationFn: () => deleteDevice(deviceId),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: deviceKeys.detail(deviceId) });
      await queryClient.invalidateQueries({ queryKey: deviceKeys.lists() });
      await navigate({ to: "/devices" });
    },
  });

  if (!device.isSuccess) return null;

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <dl className="grid grid-cols-2 gap-4 sm:grid-cols-3">
          <Fact label="Manufacturer" value={device.data.manufacturer} />
          <Fact label="Model" value={device.data.model} />
          <Fact label="Android" value={`${device.data.androidVersion} (API ${device.data.sdkInt})`} />
          <Fact label="App version" value={device.data.appVersion} />
          <Fact label="Paired" value={formatRelativeTime(device.data.pairedAt)} />
        </dl>
      </Card>

      <Card className="flex flex-col gap-4">
        <RenameForm device={device.data} />
        <PingButton device={device.data} />
      </Card>

      <DeviceInfoCards deviceId={deviceId} online={device.data.status.online} />

      <Card className="flex flex-col gap-3">
        <h2 className="font-medium">Unpair</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          The phone is disconnected and has to be paired again. You can restore it from the unpaired list.
        </p>
        {unpair.isError && <Alert tone="error">{errorMessage(unpair.error)}</Alert>}
        <DialogTrigger>
          <Button variant="destructive" className="self-start">
            Unpair device
          </Button>
          <Modal>
            <AlertDialog
              title="Unpair this device?"
              variant="destructive"
              actionLabel="Unpair"
              onAction={() => unpair.mutate()}
            >
              {device.data.name} will be disconnected immediately.
            </AlertDialog>
          </Modal>
        </DialogTrigger>
      </Card>
    </div>
  );
}
