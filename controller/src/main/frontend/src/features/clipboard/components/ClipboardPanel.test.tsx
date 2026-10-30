import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { RealtimeError, type Envelope, type RealtimeClient } from "@/shared/ws/client";
import { RealtimeClientProvider } from "@/shared/ws/RealtimeProvider";
import { ClipboardPanel } from "./ClipboardPanel";

function fakeClient(request: (type: string, payload: unknown) => Promise<Envelope>) {
  const listeners = new Set<(message: Envelope) => void>();
  const client = {
    request: vi.fn(request),
    subscribe: (listener: (message: Envelope) => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
  const push = (message: Envelope) => act(() => listeners.forEach((listener) => listener(message)));
  return { client, push };
}

describe("ClipboardPanel", () => {
  it("sends text to the phone and shows text the phone sent", async () => {
    const { client, push } = fakeClient(async (type) => ({ v: 1, type: `${type}.result`, payload: {} }));
    render(
      <RealtimeClientProvider client={client as unknown as RealtimeClient}>
        <ClipboardPanel client={client} deviceId="d1" online />
      </RealtimeClientProvider>,
    );
    const user = userEvent.setup();
    await user.type(screen.getByRole("textbox", { name: "Text" }), "hello phone");
    await user.click(screen.getByRole("button", { name: "Send to phone" }));
    expect(client.request).toHaveBeenCalledWith("clipboard.set", { deviceId: "d1", text: "hello phone" });
    expect(await screen.findByText("Copied to the phone's clipboard.")).toBeInTheDocument();

    push({ v: 1, type: "clipboard.changed", payload: { deviceId: "other", text: "not mine", receivedAt: "2026-10-29T10:00:00Z" } });
    push({ v: 1, type: "clipboard.changed", payload: { deviceId: "d1", text: "from the phone", receivedAt: "2026-10-29T10:00:00Z" } });
    expect(screen.getByText("from the phone")).toBeInTheDocument();
    expect(screen.queryByText("not mine")).toBeNull();
    expect(screen.getByRole("button", { name: "Copy" })).toBeInTheDocument();
  });

  it("explains relay failures and blocks oversized text", async () => {
    const { client } = fakeClient(async () => {
      throw new RealtimeError("DEVICE_OFFLINE", "Device is offline");
    });
    render(
      <RealtimeClientProvider client={client as unknown as RealtimeClient}>
        <ClipboardPanel client={client} deviceId="d1" online />
      </RealtimeClientProvider>,
    );
    const user = userEvent.setup();
    const field = screen.getByRole("textbox", { name: "Text" });
    await user.type(field, "x");
    await user.click(screen.getByRole("button", { name: "Send to phone" }));
    expect(await screen.findByText("The phone is offline.")).toBeInTheDocument();

    await user.clear(field);
    await user.click(field);
    await user.paste("y".repeat(16_001));
    expect(screen.getByRole("button", { name: "Send to phone" })).toBeDisabled();
  });
});
