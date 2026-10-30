import { createFileRoute } from "@tanstack/react-router";
import { FilesPage } from "@/features/files/pages/FilesPage";

type FilesSearch = { path?: string };

export const Route = createFileRoute("/_authenticated/devices/$deviceId/files")({
  validateSearch: (search: Record<string, unknown>): FilesSearch =>
    typeof search.path === "string" && search.path.startsWith("/") ? { path: search.path } : {},
  component: function FilesRoute() {
    const { deviceId } = Route.useParams();
    const { path } = Route.useSearch();
    const navigate = Route.useNavigate();
    return (
      <FilesPage
        deviceId={deviceId}
        path={path ?? null}
        onNavigate={(next) => void navigate({ search: next ? { path: next } : {} })}
      />
    );
  },
});
