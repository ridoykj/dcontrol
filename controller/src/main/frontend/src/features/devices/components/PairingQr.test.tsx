import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PairingQr } from "./PairingQr";

describe("PairingQr", () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(new Date("2026-10-29T10:00:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("counts down to expiry and offers a new code", async () => {
    const onRegenerate = vi.fn();
    render(<PairingQr payload="dcontrol://pair?v=1&u=x&t=y" expiresAt="2026-10-29T10:03:00Z" onRegenerate={onRegenerate} />);

    expect(screen.getByText("Expires in 3:00")).toBeInTheDocument();
    expect(screen.getByLabelText("Pairing QR code")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(61_000);
    });
    expect(screen.getByText("Expires in 1:59")).toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(120_000);
    });
    expect(screen.getByText("This code has expired.")).toBeInTheDocument();

    await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(
      screen.getByRole("button", { name: "Generate new code" }),
    );
    expect(onRegenerate).toHaveBeenCalledOnce();
  });
});
