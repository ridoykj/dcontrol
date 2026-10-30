import { File, Folder, MoreHorizontal } from "lucide-react";
import { useMemo, useState } from "react";
import type { Selection, SortDescriptor } from "react-aria-components";
import { Button } from "@/shared/components/ui/shadcn/Button";
import { Menu, MenuItem, MenuTrigger } from "@/shared/components/ui/shadcn/Menu";
import { Cell, Column, Row, Table, TableBody, TableHeader } from "@/shared/components/ui/shadcn/Table";
import { formatBytes } from "@/shared/lib/utils";
import type { FileEntry } from "../schemas/filesSchemas";

export type EntryAction = "download" | "rename" | "delete";

type FileTableProps = {
  entries: FileEntry[];
  selected: Selection;
  onSelectionChange: (selection: Selection) => void;
  onOpen: (entry: FileEntry) => void;
  onAction: (entry: FileEntry, action: EntryAction) => void;
};

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });

/** Directories always come first; within each group the chosen column decides. */
export function sortEntries(entries: FileEntry[], sort: SortDescriptor): FileEntry[] {
  const factor = sort.direction === "descending" ? -1 : 1;
  const compare = (a: FileEntry, b: FileEntry) => {
    switch (sort.column) {
      case "size":
        return a.size - b.size;
      case "modified":
        return a.modified.localeCompare(b.modified);
      default:
        return a.name.localeCompare(b.name, undefined, { sensitivity: "base", numeric: true });
    }
  };
  return [...entries].sort((a, b) => (a.dir === b.dir ? factor * compare(a, b) : a.dir ? -1 : 1));
}

export function FileTable({ entries, selected, onSelectionChange, onOpen, onAction }: FileTableProps) {
  const [sort, setSort] = useState<SortDescriptor>({ column: "name", direction: "ascending" });
  const sorted = useMemo(() => sortEntries(entries, sort), [entries, sort]);

  return (
    <Table
      aria-label="Files"
      className="max-h-[60vh]"
      selectionMode="multiple"
      selectionBehavior="toggle"
      selectedKeys={selected}
      onSelectionChange={onSelectionChange}
      sortDescriptor={sort}
      onSortChange={setSort}
      onRowAction={(key) => {
        const entry = sorted.find((candidate) => candidate.path === key);
        if (entry?.dir) onOpen(entry);
      }}
    >
      <TableHeader>
        <Column id="name" isRowHeader allowsSorting minWidth={200}>
          Name
        </Column>
        <Column id="size" allowsSorting width={110}>
          Size
        </Column>
        <Column id="modified" allowsSorting width={190}>
          Modified
        </Column>
        <Column id="actions" width={60}>
          <span className="sr-only">Actions</span>
        </Column>
      </TableHeader>
      <TableBody items={sorted} renderEmptyState={() => <p className="p-4 text-sm text-neutral-500">This folder is empty.</p>}>
        {(entry) => (
          <Row id={entry.path} textValue={entry.name}>
            <Cell>
              <span className={`flex items-center gap-2 ${entry.hidden ? "opacity-60" : ""}`}>
                {entry.dir ? (
                  <Folder aria-hidden className="h-4 w-4 shrink-0 text-blue-600" />
                ) : (
                  <File aria-hidden className="h-4 w-4 shrink-0 text-neutral-500" />
                )}
                <span className="truncate">{entry.name}</span>
              </span>
            </Cell>
            <Cell>{entry.dir ? "—" : formatBytes(entry.size)}</Cell>
            <Cell>{dateFormat.format(new Date(entry.modified))}</Cell>
            <Cell>
              <MenuTrigger>
                <Button variant="quiet" aria-label={`Actions for ${entry.name}`}>
                  <MoreHorizontal aria-hidden className="h-4 w-4" />
                </Button>
                <Menu onAction={(key) => onAction(entry, key as EntryAction)}>
                  {!entry.dir ? <MenuItem id="download">Download</MenuItem> : null}
                  <MenuItem id="rename">Rename</MenuItem>
                  <MenuItem id="delete">Delete</MenuItem>
                </Menu>
              </MenuTrigger>
            </Cell>
          </Row>
        )}
      </TableBody>
    </Table>
  );
}
