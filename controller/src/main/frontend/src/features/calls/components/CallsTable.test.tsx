import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { CallEntry } from "../schemas/callsSchemas";
import { CallsTable } from "./CallsTable";

const calls: CallEntry[] = [
  {
    id: 1,
    number: "+491512345678",
    presentation: "ALLOWED",
    name: "Alice Example",
    type: "MISSED",
    date: "2026-10-29T10:15:30.123Z",
    durationSec: 0,
    simLabel: "Work",
    isNew: true,
  },
  {
    id: 2,
    number: null,
    presentation: "RESTRICTED",
    name: null,
    type: "INCOMING",
    date: "2026-10-29T09:00:00Z",
    durationSec: 3725,
    simLabel: null,
    isNew: false,
  },
  {
    id: 3,
    number: "*86",
    presentation: "ALLOWED",
    name: null,
    type: "OUTGOING",
    date: "2026-10-28T09:00:00Z",
    durationSec: 65,
    simLabel: null,
    isNew: false,
  },
];

describe("CallsTable", () => {
  it("shows who called, the type and the duration", () => {
    render(<CallsTable calls={calls} selected={new Set()} onSelectionChange={vi.fn()} onCall={vi.fn()} onMessage={vi.fn()} />);
    const rows = screen.getAllByRole("row").slice(1);
    expect(within(rows[0]!).getByText("Alice Example")).toBeInTheDocument();
    expect(within(rows[0]!).getByText("Missed")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("Private number")).toBeInTheDocument();
    expect(within(rows[1]!).getByText("1:02:05")).toBeInTheDocument();
    expect(within(rows[2]!).getByText("1:05")).toBeInTheDocument();
  });

  it("offers call and message only for dialable numbers", async () => {
    const onCall = vi.fn();
    const onMessage = vi.fn();
    render(<CallsTable calls={calls} selected={new Set()} onSelectionChange={vi.fn()} onCall={onCall} onMessage={onMessage} />);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Call Alice Example" }));
    expect(onCall).toHaveBeenCalledWith(expect.objectContaining({ id: 1, number: "+491512345678" }));
    await user.click(screen.getByRole("button", { name: "Message Alice Example" }));
    expect(onMessage).toHaveBeenCalledWith("+491512345678");

    expect(screen.queryByRole("button", { name: "Call Private number" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Call *86" })).toBeNull();
  });

  it("reports selected rows", async () => {
    const onSelectionChange = vi.fn();
    render(
      <CallsTable calls={calls} selected={new Set()} onSelectionChange={onSelectionChange} onCall={vi.fn()} onMessage={vi.fn()} />,
    );
    const rows = screen.getAllByRole("row").slice(1);
    await userEvent.setup().click(within(rows[1]!).getByRole("checkbox"));
    const selection = onSelectionChange.mock.calls[0]?.[0] as Set<number>;
    expect([...selection]).toEqual([2]);
  });
});
