import { z } from "zod";

/** Envelope of protocol v1 (controller/docs/protocol/ws-v1.md). */
export const envelopeSchema = z.object({
  v: z.literal(1),
  type: z.string(),
  requestId: z.string().optional(),
  payload: z.unknown().optional(),
  error: z
    .object({
      code: z.string(),
      message: z.string(),
      details: z.object({ permission: z.string().optional() }).optional(),
    })
    .optional(),
});

export type Envelope = z.infer<typeof envelopeSchema>;

export class RealtimeError extends Error {
  readonly code: string;
  /** For PERMISSION_REQUIRED: the phone access that is missing (CONTACTS, CALL_LOG, SMS, PHONE, LOCATION, ACCESSIBILITY). */
  readonly permission: string | undefined;

  constructor(code: string, message: string, permission?: string) {
    super(message);
    this.name = "RealtimeError";
    this.code = code;
    this.permission = permission;
  }
}

export type ConnectionState = "idle" | "connecting" | "open" | "closed";

type Pending = {
  resultType: string;
  resolve: (envelope: Envelope) => void;
  reject: (error: Error) => void;
  timer: ReturnType<typeof setTimeout>;
};

export type RealtimeOptions = {
  /** Exchanges the session for a one-time ticket; return null when signed out. */
  getTicket: () => Promise<string | null>;
  url: (ticket: string) => string;
  WebSocketImpl?: typeof WebSocket;
  random?: () => number;
  maxBackoffMs?: number;
  requestTimeoutMs?: number;
};

const CLOSE_UNAUTHORIZED = 4001;

/**
 * Browser side of /ws/web: reconnects with full-jitter exponential backoff, dispatches pushed messages and
 * correlates command results by requestId.
 */
export class RealtimeClient {
  private socket: WebSocket | null = null;
  private attempt = 0;
  private stopped = true;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private readonly pending = new Map<string, Pending>();
  private readonly listeners = new Set<(message: Envelope) => void>();
  private readonly stateListeners = new Set<(state: ConnectionState) => void>();
  private state: ConnectionState = "idle";
  private readonly options: Required<RealtimeOptions>;

  constructor(options: RealtimeOptions) {
    this.options = {
      WebSocketImpl: WebSocket,
      random: Math.random,
      maxBackoffMs: 30_000,
      requestTimeoutMs: 15_000,
      ...options,
    };
  }

  get connectionState(): ConnectionState {
    return this.state;
  }

  start() {
    if (!this.stopped) return;
    this.stopped = false;
    void this.connect();
  }

  stop() {
    this.stopped = true;
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.socket?.close(1000);
    this.socket = null;
    this.failPending(new RealtimeError("DISCONNECTED", "Connection closed"));
    this.setState("idle");
  }

  subscribe(listener: (message: Envelope) => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  onStateChange(listener: (state: ConnectionState) => void): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  /** Sends a command and resolves with the matching {@code <type>.result}; rejects on error or timeout. */
  request(type: string, payload: unknown): Promise<Envelope> {
    const socket = this.socket;
    if (!socket || socket.readyState !== this.options.WebSocketImpl.OPEN) {
      return Promise.reject(new RealtimeError("DISCONNECTED", "Not connected"));
    }
    const requestId = crypto.randomUUID();
    return new Promise<Envelope>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(requestId);
        reject(new RealtimeError("TIMEOUT", "No answer in time"));
      }, this.options.requestTimeoutMs);
      this.pending.set(requestId, { resultType: `${type}.result`, resolve, reject, timer });
      socket.send(JSON.stringify({ v: 1, type, requestId, payload }));
    });
  }

  /** Sends a message that has no result (e.g. {@code rtc.signal}); returns false when not connected. */
  send(type: string, payload: unknown): boolean {
    const socket = this.socket;
    if (!socket || socket.readyState !== this.options.WebSocketImpl.OPEN) return false;
    socket.send(JSON.stringify({ v: 1, type, payload }));
    return true;
  }

  /** Delay before the given reconnect attempt: random(0, min(max, 1 s × 2^attempt)). */
  backoffDelay(attempt: number): number {
    const ceiling = Math.min(this.options.maxBackoffMs, 1000 * 2 ** attempt);
    return Math.floor(this.options.random() * ceiling);
  }

  private async connect() {
    if (this.stopped) return;
    this.setState("connecting");
    let ticket: string | null = null;
    try {
      ticket = await this.options.getTicket();
    } catch {
      ticket = null;
    }
    if (this.stopped) return;
    if (!ticket) {
      this.scheduleReconnect();
      return;
    }
    const socket = new this.options.WebSocketImpl(this.options.url(ticket));
    this.socket = socket;
    socket.onopen = () => {
      this.attempt = 0;
      this.setState("open");
    };
    socket.onmessage = (event: MessageEvent) => this.handleMessage(event.data);
    socket.onclose = (event: CloseEvent) => {
      if (this.socket !== socket) return;
      this.socket = null;
      this.failPending(new RealtimeError("DISCONNECTED", "Connection closed"));
      this.setState("closed");
      if (event.code === CLOSE_UNAUTHORIZED) this.attempt = 0;
      this.scheduleReconnect();
    };
  }

  private handleMessage(data: unknown) {
    if (typeof data !== "string") return;
    let parsed: Envelope;
    try {
      const result = envelopeSchema.safeParse(JSON.parse(data));
      if (!result.success) return;
      parsed = result.data;
    } catch {
      return;
    }
    if (parsed.requestId && this.pending.has(parsed.requestId)) {
      const entry = this.pending.get(parsed.requestId)!;
      if (parsed.type === entry.resultType || parsed.type === "error") {
        this.pending.delete(parsed.requestId);
        clearTimeout(entry.timer);
        if (parsed.error) {
          entry.reject(new RealtimeError(parsed.error.code, parsed.error.message, parsed.error.details?.permission));
        } else entry.resolve(parsed);
        return;
      }
    }
    this.listeners.forEach((listener) => listener(parsed));
  }

  private scheduleReconnect() {
    if (this.stopped) return;
    const delay = this.backoffDelay(this.attempt);
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.connect();
    }, delay);
  }

  private failPending(error: Error) {
    this.pending.forEach((entry) => {
      clearTimeout(entry.timer);
      entry.reject(error);
    });
    this.pending.clear();
  }

  private setState(state: ConnectionState) {
    this.state = state;
    this.stateListeners.forEach((listener) => listener(state));
  }
}

/** What feature code needs from the client to send commands; tests pass a fake. */
export type Requester = Pick<RealtimeClient, "request">;
