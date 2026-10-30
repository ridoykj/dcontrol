import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState } from "react";
import { Button } from "@/shared/components/ui/shadcn/Button";

type PairingQrProps = {
  payload: string;
  expiresAt: string;
  onRegenerate: () => void;
  isRegenerating?: boolean;
};

function remainingSeconds(expiresAt: string): number {
  return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - Date.now()) / 1000));
}

function formatCountdown(seconds: number): string {
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(seconds % 60).padStart(2, "0")}`;
}

/** QR code with a live countdown; once expired it is hidden and can be regenerated. */
export function PairingQr({ payload, expiresAt, onRegenerate, isRegenerating }: PairingQrProps) {
  const [remaining, setRemaining] = useState(() => remainingSeconds(expiresAt));

  useEffect(() => {
    setRemaining(remainingSeconds(expiresAt));
    const timer = setInterval(() => setRemaining(remainingSeconds(expiresAt)), 1000);
    return () => clearInterval(timer);
  }, [expiresAt]);

  const expired = remaining === 0;

  return (
    <div className="flex flex-col items-center gap-4">
      <div className="relative rounded-xl bg-white p-4">
        <QRCodeSVG
          value={payload}
          size={232}
          level="M"
          aria-label="Pairing QR code"
          className={expired ? "opacity-10 blur-sm" : undefined}
        />
        {expired && (
          <div className="absolute inset-0 flex items-center justify-center">
            <Button onPress={onRegenerate} isPending={isRegenerating}>
              Generate new code
            </Button>
          </div>
        )}
      </div>
      <p className="text-sm text-neutral-600 dark:text-neutral-400" aria-live="polite">
        {expired ? "This code has expired." : `Expires in ${formatCountdown(remaining)}`}
      </p>
    </div>
  );
}
