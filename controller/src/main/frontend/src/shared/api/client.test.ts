import { describe, expect, it, vi } from "vitest";
import { createApiClient } from "./client";
import { ApiError } from "./problem";
import { createSessionStore } from "./session";

const user = { id: "u1", accountId: "a1", email: "ada@example.com", displayName: "Ada" };

function json(status: number, body: unknown, contentType = "application/json") {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": contentType } });
}

function tokens(accessToken: string) {
  return json(200, { accessToken, tokenType: "Bearer", expiresIn: 900, user });
}

describe("createApiClient", () => {
  it("refreshes once for concurrent 401s and retries with the new token", async () => {
    const store = createSessionStore();
    store.set({ accessToken: "expired", user });
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/auth/refresh") return tokens("fresh");
      const auth = new Headers(init?.headers).get("Authorization");
      return auth === "Bearer fresh" ? json(200, { ok: true }) : json(401, { code: "UNAUTHORIZED" });
    });
    const client = createApiClient(store, fetchMock as typeof fetch);

    const results = await Promise.all([
      client.request("/api/a", null),
      client.request("/api/b", null),
      client.request("/api/c", null),
    ]);

    expect(results).toHaveLength(3);
    expect(fetchMock.mock.calls.filter(([url]) => url === "/api/auth/refresh")).toHaveLength(1);
    expect(store.get().accessToken).toBe("fresh");
  });

  it("clears the session when refresh fails twice", async () => {
    vi.useFakeTimers();
    const store = createSessionStore();
    store.set({ accessToken: "expired", user });
    const fetchMock = vi.fn(async () => json(401, { code: "TOKEN_INVALID" }));
    const client = createApiClient(store, fetchMock as typeof fetch);

    const pending = client.refresh();
    await vi.advanceTimersByTimeAsync(400);
    expect(await pending).toBe(false);
    expect(store.get().accessToken).toBeNull();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.useRealTimers();
  });

  it("turns problem details into ApiError", async () => {
    const store = createSessionStore();
    const fetchMock = vi.fn(async () =>
      json(
        400,
        {
          status: 400,
          detail: "Request validation failed",
          code: "VALIDATION",
          traceId: "t-1",
          errors: [{ field: "email", message: "must be a well-formed email address" }],
        },
        "application/problem+json",
      ),
    );
    const client = createApiClient(store, fetchMock as typeof fetch);

    const error = (await client
      .request("/api/auth/register", null, { json: {}, auth: false })
      .catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("VALIDATION");
    expect(error.fieldErrors[0]?.field).toBe("email");
    expect(error.traceId).toBe("t-1");
  });

  it("sends the CSRF header and no bearer token when auth is false", async () => {
    const store = createSessionStore();
    store.set({ accessToken: "token", user });
    const fetchMock = vi.fn(async (_input: RequestInfo | URL, _init?: RequestInit) => json(200, {}));
    const client = createApiClient(store, fetchMock as typeof fetch);

    await client.request("/api/auth/login", null, { json: { email: "x" }, auth: false });
    const headers = new Headers(fetchMock.mock.calls[0]![1]!.headers);
    expect(headers.get("Authorization")).toBeNull();
    expect(headers.get("X-Requested-With")).toBe("XMLHttpRequest");
  });
});
