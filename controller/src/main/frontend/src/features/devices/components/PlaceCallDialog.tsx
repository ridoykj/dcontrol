import { useState } from "react";
import { Heading } from "react-aria-components";
import { Alert } from "@/shared/components/Alert";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Dialog } from "@/shared/components/ui/shadcn/Dialog";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { useSims } from "../api/simsApi";
import { missingPermission, PermissionRequired } from "./PermissionRequired";
import { relayErrorMessage } from "./relayErrorMessage";
import { SimPicker } from "./SimPicker";

/** Same rule as the controller's DialNumber: digits with separators, no * or # (no MMI/USSD codes). */
export function dialable(number: string): boolean {
  if (number.length > 40 || !/^[+0-9 ().-]+$/.test(number)) return false;
  return /^\+?[0-9]{2,20}$/.test(number.replace(/[ ().-]/g, ""));
}

type PlaceCallDialogProps = { deviceId: string; number: string; name?: string | null };

/** Confirms before the phone dials, because the call really starts on the phone. */
export function PlaceCallDialog({ deviceId, number, name }: PlaceCallDialogProps) {
  const { client } = useRealtime();
  const sims = useSims(deviceId, true);
  const [simId, setSimId] = useState<number | null>(null);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<unknown>(null);
  const [placed, setPlaced] = useState(false);
  const permission = missingPermission(failure);
  const valid = dialable(number);

  return (
    <Dialog role="alertdialog">
      {({ close }) => (
        <div className="flex flex-col gap-4">
          <Heading slot="title" className="text-xl font-semibold">
            Call {name ?? number}?
          </Heading>
          {valid ? (
            <p className="text-sm text-neutral-600 dark:text-neutral-400">
              Your phone will dial <strong className="tabular-nums">{number}</strong>. Talk on the phone; the call
              doesn't go through the browser.
            </p>
          ) : (
            <Alert tone="error">
              This number can't be dialed from the web. Only digits, an optional leading +, spaces, dashes and
              parentheses are allowed; codes with * or # must be entered on the phone.
            </Alert>
          )}
          <SimPicker sims={sims.data?.sims ?? []} value={simId} onChange={setSimId} />
          {permission && <PermissionRequired permission={permission} />}
          {failure !== null && !permission && <Alert tone="error">{relayErrorMessage(failure)}</Alert>}
          {placed && <Alert tone="success">The phone is dialing.</Alert>}
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onPress={close}>
              {placed ? "Close" : "Cancel"}
            </Button>
            {!placed && (
              <Button
                isDisabled={!valid}
                isPending={pending}
                onPress={async () => {
                  setPending(true);
                  setFailure(null);
                  try {
                    await client.request("call.place", { deviceId, number, simId });
                    setPlaced(true);
                  } catch (error) {
                    setFailure(error);
                  } finally {
                    setPending(false);
                  }
                }}
              >
                Call
              </Button>
            )}
          </div>
        </div>
      )}
    </Dialog>
  );
}
