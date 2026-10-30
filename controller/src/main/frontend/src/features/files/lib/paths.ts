import type { Volume } from "@/features/devices/schemas/deviceInfoSchemas";

export type Crumb = { label: string; path: string };

export function joinPath(dir: string, name: string): string {
  return dir.endsWith("/") ? `${dir}${name}` : `${dir}/${name}`;
}

export function parentOf(path: string): string {
  const trimmed = path.replace(/\/+$/, "");
  const index = trimmed.lastIndexOf("/");
  return index <= 0 ? "/" : trimmed.slice(0, index);
}

/** The volume a path lives on: the one with the longest matching root. */
export function volumeOf(path: string, volumes: Volume[]): Volume | undefined {
  return volumes
    .filter((volume) => path === volume.path || path.startsWith(`${volume.path}/`))
    .sort((a, b) => b.path.length - a.path.length)[0];
}

/**
 * Breadcrumbs from the volume root down to {@code path}, labelled with the volume name instead of
 * "/storage/emulated/0". Paths outside every volume are shown segment by segment from "/".
 */
export function crumbs(path: string, volumes: Volume[]): Crumb[] {
  const volume = volumeOf(path, volumes);
  const root = volume ? { label: volume.label, path: volume.path } : { label: "/", path: "/" };
  const rest = path.slice(root.path.length).split("/").filter(Boolean);
  const result: Crumb[] = [root];
  let current = root.path;
  for (const segment of rest) {
    current = joinPath(current, segment);
    result.push({ label: segment, path: current });
  }
  return result;
}
