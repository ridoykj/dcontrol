import { useSyncExternalStore } from "react";

export type SessionUser = {
  id: string;
  accountId: string;
  email: string;
  displayName: string;
};

export type Session = {
  accessToken: string | null;
  user: SessionUser | null;
};

type Listener = () => void;

/** In-memory session. The access token is never persisted; the refresh token lives in an HttpOnly cookie. */
export function createSessionStore() {
  let state: Session = { accessToken: null, user: null };
  const listeners = new Set<Listener>();

  return {
    get: () => state,
    set(next: Session) {
      state = next;
      listeners.forEach((listener) => listener());
    },
    clear() {
      this.set({ accessToken: null, user: null });
    },
    subscribe(listener: Listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export type SessionStore = ReturnType<typeof createSessionStore>;

export const sessionStore = createSessionStore();

export function useSession(): Session {
  return useSyncExternalStore(sessionStore.subscribe, sessionStore.get, sessionStore.get);
}
