import { describe, expect, it } from "vitest";
import { ago, formatAccuracy, formatCoordinates, lockFormSchema, mapLinks, storedLocationSchema } from "./findSchemas";

describe("location formatting", () => {
  it("formats coordinates with hemispheres and accuracy in m or km", () => {
    expect(formatCoordinates(23.810331, 90.412521)).toBe("23.810331° N, 90.412521° E");
    expect(formatCoordinates(-33.8688, -151.2093)).toBe("33.868800° S, 151.209300° W");
    expect(formatAccuracy(12.5)).toBe("±13 m");
    expect(formatAccuracy(2500)).toBe("±2.5 km");
  });

  it("builds map links that carry only the coordinates", () => {
    const links = mapLinks(23.810331, 90.412521);
    expect(links.openStreetMap).toBe(
      "https://www.openstreetmap.org/?mlat=23.810331&mlon=90.412521#map=17/23.810331/90.412521",
    );
    expect(links.googleMaps).toBe("https://www.google.com/maps/search/?api=1&query=23.810331%2C90.412521");
  });

  it("says how long ago a fix was taken", () => {
    const now = Date.parse("2026-10-29T12:00:00Z");
    expect(ago("2026-10-29T11:59:45Z", now)).toBe("just now");
    expect(ago("2026-10-29T11:55:00Z", now)).toBe("5 min ago");
    expect(ago("2026-10-29T09:00:00Z", now)).toBe("3 h ago");
  });

  it("parses the controller's stored location", () => {
    expect(
      storedLocationSchema.safeParse({
        deviceId: "d1",
        latitude: 1,
        longitude: 2,
        accuracyM: 3,
        altitudeM: null,
        fixedAt: "2026-10-29T10:15:30.123Z",
        provider: "NETWORK",
        stale: true,
        updatedAt: "2026-10-29T10:15:31Z",
      }).success,
    ).toBe(true);
  });
});

describe("lockFormSchema", () => {
  it("matches the controller's find.lock rules", () => {
    expect(lockFormSchema.safeParse({ message: "Lost phone,\ncall me", contact: "+880 1700-000000" }).success).toBe(true);
    expect(lockFormSchema.safeParse({ message: "Lost phone", contact: "" }).success).toBe(true);
    expect(lockFormSchema.safeParse({ message: "   ", contact: "" }).success).toBe(false);
    expect(lockFormSchema.safeParse({ message: "x".repeat(201), contact: "" }).success).toBe(false);
    expect(lockFormSchema.safeParse({ message: "bell\u0007", contact: "" }).success).toBe(false);
    expect(lockFormSchema.safeParse({ message: "ok", contact: "tab\there" }).success).toBe(false);
    expect(lockFormSchema.safeParse({ message: "ok", contact: "1".repeat(65) }).success).toBe(false);
  });
});
