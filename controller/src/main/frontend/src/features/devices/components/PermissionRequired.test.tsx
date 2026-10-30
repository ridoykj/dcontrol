import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ApiError } from "@/shared/api/problem";
import { RealtimeError } from "@/shared/ws/client";
import { missingPermission, PermissionRequired } from "./PermissionRequired";
import { dialable } from "./PlaceCallDialog";

describe("missingPermission", () => {
  it("names the permission of a PERMISSION_REQUIRED error only", () => {
    expect(missingPermission(new RealtimeError("PERMISSION_REQUIRED", "x", "CALL_LOG"))).toBe("CALL_LOG");
    expect(missingPermission(new RealtimeError("PERMISSION_REQUIRED", "x"))).toBeNull();
    expect(missingPermission(new RealtimeError("PERMISSION_REQUIRED", "x", "CAMERA"))).toBe("CAMERA");
    expect(missingPermission(new RealtimeError("PERMISSION_REQUIRED", "x", "MICROPHONE"))).toBe("MICROPHONE");
    expect(missingPermission(new RealtimeError("PERMISSION_REQUIRED", "x", "ACCESSIBILITY"))).toBe("ACCESSIBILITY");
    expect(missingPermission(new ApiError(409, { code: "PERMISSION_REQUIRED", permission: "LOCATION" }))).toBe(
      "LOCATION",
    );
    expect(missingPermission(new ApiError(409, { code: "DEVICE_OFFLINE", permission: "LOCATION" }))).toBeNull();
    expect(missingPermission(new RealtimeError("NOT_FOUND", "x", "SMS"))).toBeNull();
    expect(missingPermission(new Error("x"))).toBeNull();
  });
});

describe("PermissionRequired", () => {
  it("points at the card on the phone and the restricted-settings step for SMS", () => {
    const { rerender } = render(<PermissionRequired permission="CONTACTS" />);
    expect(screen.getByText("Contacts")).toBeInTheDocument();
    expect(screen.queryByText(/Allow restricted settings/)).toBeNull();

    rerender(<PermissionRequired permission="CAMERA" />);
    expect(screen.getByText("Camera")).toBeInTheDocument();
    expect(screen.getByText(/use the camera/)).toBeInTheDocument();

    rerender(<PermissionRequired permission="MICROPHONE" />);
    expect(screen.getByText("Microphone")).toBeInTheDocument();
    expect(screen.getByText(/use the microphone/)).toBeInTheDocument();

    rerender(<PermissionRequired permission="SMS" />);
    expect(screen.getByText("Messages")).toBeInTheDocument();
    expect(screen.getByText(/Allow restricted settings/)).toBeInTheDocument();
  });
});

describe("dialable", () => {
  it("matches the controller's DialNumber rule", () => {
    for (const ok of ["+491512345678", "+49 (151) 234-5678", "0151.234.5678", "112"]) expect(dialable(ok)).toBe(true);
    for (const bad of ["*#06#", "**21*0151#", "1", "+49 151 abc", "0151;1", "1".repeat(21), ""]) {
      expect(dialable(bad)).toBe(false);
    }
  });
});
