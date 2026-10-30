import { AlertCircle, CheckCircle2, Info } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/shared/lib/utils";

type AlertProps = {
  tone?: "error" | "success" | "info";
  children: ReactNode;
  className?: string;
};

const tones = {
  error: {
    icon: AlertCircle,
    classes: "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950 dark:text-red-200",
  },
  success: {
    icon: CheckCircle2,
    classes:
      "border-green-200 bg-green-50 text-green-800 dark:border-green-900 dark:bg-green-950 dark:text-green-200",
  },
  info: {
    icon: Info,
    classes: "border-blue-200 bg-blue-50 text-blue-800 dark:border-blue-900 dark:bg-blue-950 dark:text-blue-200",
  },
};

export function Alert({ tone = "info", children, className }: AlertProps) {
  const { icon: Icon, classes } = tones[tone];
  return (
    <div
      role={tone === "error" ? "alert" : "status"}
      className={cn("flex gap-2 rounded-lg border px-3 py-2 text-sm", classes, className)}
    >
      <Icon aria-hidden className="mt-0.5 h-4 w-4 shrink-0" />
      <div>{children}</div>
    </div>
  );
}
