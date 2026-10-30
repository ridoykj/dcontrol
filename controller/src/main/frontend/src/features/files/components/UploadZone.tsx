import { Upload } from "lucide-react";
import { DropZone, FileTrigger, Text } from "react-aria-components";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { cn } from "@/shared/lib/utils";

/** Drop files here or pick them; each file becomes one upload into the current folder. */
export function UploadZone({ onFiles, isDisabled }: { onFiles: (files: File[]) => void; isDisabled?: boolean }) {
  return (
    <DropZone
      isDisabled={isDisabled}
      onDrop={async (event) => {
        const files: File[] = [];
        for (const item of event.items) {
          if (item.kind === "file") files.push(await item.getFile());
        }
        if (files.length > 0) onFiles(files);
      }}
      className={({ isDropTarget }) =>
        cn(
          "flex flex-wrap items-center gap-3 rounded-xl border-2 border-dashed border-neutral-300 p-4 dark:border-neutral-700",
          isDropTarget && "border-blue-500 bg-blue-50 dark:bg-blue-950",
          isDisabled && "opacity-50",
        )
      }
    >
      <Upload aria-hidden className="h-5 w-5 text-neutral-500" />
      <Text slot="label" className="flex-1 text-sm text-neutral-600 dark:text-neutral-400">
        Drop files here to upload them into this folder
      </Text>
      <FileTrigger allowsMultiple onSelect={(list) => list && onFiles(Array.from(list))}>
        <Button variant="secondary" isDisabled={isDisabled}>
          Choose files
        </Button>
      </FileTrigger>
    </DropZone>
  );
}
