import { describe, expect, it } from "vitest";
import type { Volume } from "@/features/devices/schemas/deviceInfoSchemas";
import { sortEntries } from "../components/FileTable";
import { fileNameSchema, type FileEntry } from "../schemas/filesSchemas";
import { crumbs, joinPath, parentOf, volumeOf } from "./paths";

const volumes: Volume[] = [
  { id: "primary", label: "Internal storage", path: "/storage/emulated/0", totalBytes: 10, freeBytes: 5, removable: false, primary: true },
  { id: "sd", label: "SD card", path: "/storage/1A2B-3C4D", totalBytes: 10, freeBytes: 5, removable: true, primary: false },
];

describe("paths", () => {
  it("joins and goes up", () => {
    expect(joinPath("/storage/emulated/0", "DCIM")).toBe("/storage/emulated/0/DCIM");
    expect(joinPath("/", "sdcard")).toBe("/sdcard");
    expect(parentOf("/storage/emulated/0/DCIM/")).toBe("/storage/emulated/0");
    expect(parentOf("/storage")).toBe("/");
  });

  it("finds the volume of a path without matching prefixes of other names", () => {
    expect(volumeOf("/storage/emulated/0/DCIM", volumes)?.id).toBe("primary");
    expect(volumeOf("/storage/emulated/00", volumes)).toBeUndefined();
  });

  it("labels breadcrumbs with the volume name", () => {
    expect(crumbs("/storage/emulated/0/DCIM/Camera", volumes)).toEqual([
      { label: "Internal storage", path: "/storage/emulated/0" },
      { label: "DCIM", path: "/storage/emulated/0/DCIM" },
      { label: "Camera", path: "/storage/emulated/0/DCIM/Camera" },
    ]);
    expect(crumbs("/storage/1A2B-3C4D", volumes)).toEqual([{ label: "SD card", path: "/storage/1A2B-3C4D" }]);
    expect(crumbs("/data/x", [])).toEqual([
      { label: "/", path: "/" },
      { label: "data", path: "/data" },
      { label: "x", path: "/data/x" },
    ]);
  });
});

describe("file names and sorting", () => {
  it("accepts what the phone accepts", () => {
    expect(fileNameSchema.safeParse("report 2026.pdf").success).toBe(true);
    expect(fileNameSchema.safeParse("a/b").success).toBe(false);
    expect(fileNameSchema.safeParse("..").success).toBe(false);
    expect(fileNameSchema.safeParse("   ").success).toBe(false);
    expect(fileNameSchema.safeParse("x".repeat(256)).success).toBe(false);
  });

  it("keeps folders first whatever the column", () => {
    const entry = (name: string, dir: boolean, size: number): FileEntry => ({
      name,
      path: `/p/${name}`,
      dir,
      size,
      modified: "2026-10-29T10:00:00Z",
      mime: null,
      hidden: false,
    });
    const entries = [entry("b.txt", false, 5), entry("Zeta", true, 0), entry("a10.txt", false, 50), entry("a9.txt", false, 1), entry("alpha", true, 0)];
    expect(sortEntries(entries, { column: "name", direction: "ascending" }).map((e) => e.name)).toEqual([
      "alpha",
      "Zeta",
      "a9.txt",
      "a10.txt",
      "b.txt",
    ]);
    expect(sortEntries(entries, { column: "size", direction: "descending" }).map((e) => e.name)).toEqual([
      "Zeta",
      "alpha",
      "a10.txt",
      "b.txt",
      "a9.txt",
    ]);
  });
});
