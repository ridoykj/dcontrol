import { useQuery } from "@tanstack/react-query";
import { useEffect, useMemo } from "react";
import { api } from "@/shared/api/client";
import { cn } from "@/shared/lib/utils";
import { contactKeys, contactPhotoPath } from "../api/contactsApi";

type ContactAvatarProps = {
  deviceId: string;
  contact: { id: number; lookupKey: string; name: string; photo: boolean };
  size?: "sm" | "lg";
};

function initials(name: string): string {
  const letters = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase());
  return letters.join("") || "?";
}

/** The contact's photo, pulled from the phone on demand, or the name's initials. */
export function ContactAvatar({ deviceId, contact, size = "sm" }: ContactAvatarProps) {
  const photo = useQuery({
    queryKey: contactKeys.photo(deviceId, contact.id),
    queryFn: () => api.requestBlob(contactPhotoPath(deviceId, contact.id, contact.lookupKey)),
    enabled: contact.photo,
    staleTime: 5 * 60_000,
    gcTime: 10 * 60_000,
    retry: false,
  });
  const url = useMemo(() => (photo.data ? URL.createObjectURL(photo.data) : null), [photo.data]);
  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url);
  }, [url]);

  const box = size === "lg" ? "h-16 w-16 text-xl" : "h-9 w-9 text-sm";
  if (url) return <img src={url} alt="" className={cn("shrink-0 rounded-full object-cover", box)} />;
  return (
    <span
      aria-hidden
      className={cn(
        "flex shrink-0 items-center justify-center rounded-full bg-blue-100 font-semibold text-blue-800 dark:bg-blue-950 dark:text-blue-200",
        box,
      )}
    >
      {initials(contact.name)}
    </span>
  );
}
