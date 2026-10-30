import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo } from "react";
import { api } from "@/shared/api/client";

/** App icon uploaded by the phone, or the app's first letter when there is none. */
export function AppIcon({ deviceId, hash, label }: { deviceId: string; hash?: string | null; label: string }) {
  const icon = useQuery({
    queryKey: ["app-icons", deviceId, hash],
    queryFn: () => api.requestBlob(`/api/devices/${encodeURIComponent(deviceId)}/app-icons/${hash}`),
    enabled: Boolean(hash),
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: false,
  });
  const url = useMemo(() => (icon.data ? URL.createObjectURL(icon.data) : null), [icon.data]);
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  if (url) return <img src={url} alt="" className="h-9 w-9 shrink-0 rounded-lg" />;
  return (
    <span
      aria-hidden
      className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-neutral-200 text-sm font-semibold text-neutral-700 dark:bg-neutral-800 dark:text-neutral-200"
    >
      {label.trim().charAt(0).toUpperCase() || "?"}
    </span>
  );
}
