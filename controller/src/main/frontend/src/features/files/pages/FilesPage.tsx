import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowUp, FolderPlus, HardDrive } from "lucide-react";
import { useState } from "react";
import { DialogTrigger, type Selection } from "react-aria-components";
import { deviceKeys, getDevice } from "@/features/devices/api/devicesApi";
import { relayErrorMessage } from "@/features/devices/components/relayErrorMessage";
import { Alert } from "@/shared/components/Alert";
import { Card } from "@/shared/components/Card";
import { AlertDialog } from "@/shared/components/ui/shadcn/AlertDialog";
import { Breadcrumb, Breadcrumbs } from "@/shared/components/ui/shadcn/Breadcrumbs";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Modal } from "@/shared/components/ui/shadcn/Modal";
import { ProgressBar } from "@/shared/components/ui/shadcn/ProgressBar";
import { formatBytes } from "@/shared/lib/utils";
import { RealtimeError } from "@/shared/ws/client";
import { useRealtime } from "@/shared/ws/RealtimeProvider";
import { deleteEntries, filesKeys, listDirectory, listRoots, makeDirectory, renameEntry } from "../api/filesApi";
import { FileTable, type EntryAction } from "../components/FileTable";
import { NameDialog } from "../components/NameDialog";
import { TransfersPanel } from "../components/TransfersPanel";
import { UploadZone } from "../components/UploadZone";
import { useTransfers } from "../hooks/useTransfers";
import { crumbs, parentOf, volumeOf } from "../lib/paths";
import type { FileEntry } from "../schemas/filesSchemas";

function PermissionNotice() {
  return (
    <Alert tone="info">
      The phone hasn't allowed file access yet. Open dcontrol on the phone and tap <strong>Grant</strong> on the
      Files access card (Android 11+ calls it “All files access”).
    </Alert>
  );
}

function needsPermission(error: unknown) {
  return error instanceof RealtimeError && error.code === "PERMISSION_REQUIRED";
}

type FilesPageProps = {
  deviceId: string;
  /** Directory shown, or null for the list of storage volumes. */
  path: string | null;
  onNavigate: (path: string | null) => void;
};

export function FilesPage({ deviceId, path, onNavigate }: FilesPageProps) {
  const { client } = useRealtime();
  const queryClient = useQueryClient();
  const device = useQuery({ queryKey: deviceKeys.detail(deviceId), queryFn: () => getDevice(deviceId) });
  const online = device.data?.status.online ?? false;
  const [selected, setSelected] = useState<Selection>(new Set());
  const [renaming, setRenaming] = useState<FileEntry | null>(null);
  const [deleting, setDeleting] = useState<string[] | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const refreshDir = (dir: string) => queryClient.invalidateQueries({ queryKey: filesKeys.dir(deviceId, dir) });
  const transfers = useTransfers(deviceId, (transfer) => void refreshDir(transfer.devicePath));

  const roots = useQuery({
    queryKey: filesKeys.roots(deviceId),
    queryFn: () => listRoots(client, deviceId),
    enabled: online,
    retry: false,
  });
  const listing = useInfiniteQuery({
    queryKey: filesKeys.dir(deviceId, path ?? ""),
    queryFn: ({ pageParam }) => listDirectory(client, deviceId, path!, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => last.nextOffset ?? undefined,
    enabled: online && path !== null,
    retry: false,
  });

  const volumes = roots.data ?? [];
  const entries = listing.data?.pages.flatMap((page) => page.entries) ?? [];
  const total = listing.data?.pages[0]?.total ?? 0;
  const selectedPaths =
    selected === "all" ? entries.map((entry) => entry.path) : [...selected].map((key) => String(key));
  const atVolumeRoot = path !== null && volumes.some((volume) => volume.path === path);

  const run = async (work: () => Promise<unknown>) => {
    setActionError(null);
    try {
      await work();
    } catch (error) {
      setActionError(relayErrorMessage(error));
    }
  };

  const onAction = (entry: FileEntry, action: EntryAction) => {
    if (action === "download") void run(() => transfers.download(entry.path));
    if (action === "rename") setRenaming(entry);
    if (action === "delete") setDeleting([entry.path]);
  };

  const confirmDelete = async (paths: string[]) => {
    const result = await deleteEntries(client, deviceId, paths);
    setSelected(new Set());
    await refreshDir(path!);
    const first = result.failures[0];
    if (first) {
      throw new Error(`${result.failures.length} item(s) could not be deleted: ${first.message ?? first.code}`);
    }
  };

  if (!device.isSuccess) return null;
  if (!online) return <Alert>The phone is offline. Files are read live from it, so connect it first.</Alert>;

  if (path === null) {
    return (
      <div className="flex flex-col gap-3">
        {roots.isPending && <ProgressBar aria-label="Reading storage" isIndeterminate />}
        {roots.isError &&
          (needsPermission(roots.error) ? <PermissionNotice /> : <Alert tone="error">{relayErrorMessage(roots.error)}</Alert>)}
        <div className="grid gap-3 sm:grid-cols-2">
          {volumes.map((volume) => (
            <Card key={volume.id} className="p-0">
              <Button
                variant="quiet"
                className="flex w-full items-center justify-start gap-3 p-4 text-left"
                onPress={() => onNavigate(volume.path)}
              >
                <HardDrive aria-hidden className="h-6 w-6 shrink-0 text-blue-600" />
                <span className="flex flex-col">
                  <span className="font-medium">{volume.label}</span>
                  <span className="text-xs text-neutral-500">
                    {formatBytes(volume.freeBytes)} free of {formatBytes(volume.totalBytes)}
                  </span>
                </span>
              </Button>
            </Card>
          ))}
        </div>
        <TransfersPanel
          items={transfers.items}
          onCancel={(id) => void transfers.cancel(id)}
          onSave={transfers.saveAgain}
          onDismiss={transfers.dismiss}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="secondary"
          aria-label="Up one folder"
          onPress={() => onNavigate(atVolumeRoot || !volumeOf(path, volumes) ? null : parentOf(path))}
        >
          <ArrowUp aria-hidden className="h-4 w-4" />
        </Button>
        <Breadcrumbs className="min-w-0 flex-1 flex-wrap" onAction={(key) => onNavigate(String(key))}>
          {crumbs(path, volumes).map((crumb) => (
            <Breadcrumb key={crumb.path} id={crumb.path}>
              {crumb.label}
            </Breadcrumb>
          ))}
        </Breadcrumbs>
        <DialogTrigger>
          <Button variant="secondary">
            <FolderPlus aria-hidden className="h-4 w-4" />
            <span>New folder</span>
          </Button>
          <Modal>
            <NameDialog
              title="New folder"
              label="Folder name"
              actionLabel="Create"
              onSubmit={async (name) => {
                await makeDirectory(client, deviceId, path, name);
                await refreshDir(path);
              }}
            />
          </Modal>
        </DialogTrigger>
      </div>

      {selectedPaths.length > 0 && (
        <div className="flex items-center gap-2 text-sm">
          <span>{selectedPaths.length} selected</span>
          <Button
            variant="secondary"
            onPress={() => {
              entries
                .filter((entry) => selectedPaths.includes(entry.path) && !entry.dir)
                .forEach((entry) => void run(() => transfers.download(entry.path)));
            }}
          >
            Download files
          </Button>
          <Button variant="destructive" onPress={() => setDeleting(selectedPaths)}>
            Delete
          </Button>
        </div>
      )}

      {actionError && <Alert tone="error">{actionError}</Alert>}
      {listing.isPending && <ProgressBar aria-label="Reading folder" isIndeterminate />}
      {listing.isError &&
        (needsPermission(listing.error) ? <PermissionNotice /> : <Alert tone="error">{relayErrorMessage(listing.error)}</Alert>)}
      {listing.isSuccess && (
        <>
          <FileTable
            entries={entries}
            selected={selected}
            onSelectionChange={setSelected}
            onOpen={(entry) => {
              setSelected(new Set());
              onNavigate(entry.path);
            }}
            onAction={onAction}
          />
          <div className="flex items-center justify-between text-xs text-neutral-500">
            <span>
              {entries.length} of {total} items
            </span>
            {listing.hasNextPage && (
              <Button variant="secondary" isPending={listing.isFetchingNextPage} onPress={() => void listing.fetchNextPage()}>
                Load more
              </Button>
            )}
          </div>
        </>
      )}

      <UploadZone
        isDisabled={!listing.isSuccess}
        onFiles={(files) => files.forEach((file) => void run(() => transfers.upload(file, path)))}
      />
      <TransfersPanel
        items={transfers.items}
        onCancel={(id) => void transfers.cancel(id)}
        onSave={transfers.saveAgain}
        onDismiss={transfers.dismiss}
      />

      <Modal isOpen={renaming !== null} onOpenChange={(open) => !open && setRenaming(null)}>
        {renaming && (
          <NameDialog
            title={`Rename ${renaming.dir ? "folder" : "file"}`}
            label="New name"
            actionLabel="Rename"
            initialName={renaming.name}
            onSubmit={async (name) => {
              await renameEntry(client, deviceId, renaming.path, name);
              await refreshDir(path);
            }}
          />
        )}
      </Modal>
      <Modal isOpen={deleting !== null} onOpenChange={(open) => !open && setDeleting(null)}>
        {deleting && (
          <AlertDialog
            title={deleting.length === 1 ? "Delete this item?" : `Delete ${deleting.length} items?`}
            variant="destructive"
            actionLabel="Delete"
            onAction={() => void run(() => confirmDelete(deleting))}
          >
            Folders are deleted with everything in them. This can't be undone.
          </AlertDialog>
        )}
      </Modal>
    </div>
  );
}
