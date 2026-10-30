import type { ReactNode } from "react";
import { cn } from "@/shared/lib/utils";

export function Card({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <section
      className={cn(
        "rounded-xl border border-neutral-200 bg-white p-5 shadow-sm dark:border-neutral-800 dark:bg-neutral-900",
        className,
      )}
    >
      {children}
    </section>
  );
}
