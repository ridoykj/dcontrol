import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { FileEntry } from "../schemas/filesSchemas";
import { FileTable } from "./FileTable";

const entries: FileEntry[] = [
  { name: "notes.txt", path: "/s/notes.txt", dir: false, size: 2048, modified: "2026-10-29T10:00:00Z", mime: "text/plain", hidden: false },
  { name: "DCIM", path: "/s/DCIM", dir: true, size: 0, modified: "2026-10-28T10:00:00Z", mime: null, hidden: false },
];

describe("FileTable", () => {
  it("lists folders first, opens folders and offers actions", async () => {
    const onOpen = vi.fn();
    const onAction = vi.fn();
    render(
      <FileTable entries={entries} selected={new Set()} onSelectionChange={vi.fn()} onOpen={onOpen} onAction={onAction} />,
    );

    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]!).getByText("DCIM")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("2.0 KB")).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(within(rows[0]!).getByText("DCIM"));
    expect(onOpen).toHaveBeenCalledWith(expect.objectContaining({ path: "/s/DCIM" }));

    await user.click(screen.getByRole("button", { name: "Actions for notes.txt" }));
    await user.click(await screen.findByRole("menuitem", { name: "Download" }));
    expect(onAction).toHaveBeenCalledWith(expect.objectContaining({ name: "notes.txt" }), "download");
  });

  it("does not offer download for folders", async () => {
    render(
      <FileTable entries={entries} selected={new Set()} onSelectionChange={vi.fn()} onOpen={vi.fn()} onAction={vi.fn()} />,
    );
    await userEvent.setup().click(screen.getByRole("button", { name: "Actions for DCIM" }));
    expect(await screen.findByRole("menuitem", { name: "Rename" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Download" })).toBeNull();
  });
});
