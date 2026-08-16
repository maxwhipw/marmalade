// ntfy.ts — the cheap SECONDARY alert path (hardening item #2; design note
// kept internally).
// The Android client's always-on WS foreground service is the PRIMARY mobile
// notification mechanism; ntfy covers daemon-side alerts (silent failures,
// cron fire errors, budget pauses) and non-phone targets. Off unless a topic
// is configured (config.ts `ntfy` block). No dependencies: global fetch, one
// POST per alert, no retry queue.

export interface NtfyConfig {
  /** Base URL of the ntfy server (default https://ntfy.sh). */
  server: string;
  /** Topic to publish to — configuring one is the feature switch. */
  topic: string;
  /** Optional bearer token for auth-protected topics. */
  token?: string;
}

export interface NtfyPublishOpts {
  /** ntfy priority 1 (min) … 5 (max); omitted = server default. */
  priority?: number;
}

const TIMEOUT_MS = 5_000;

export class NtfyNotifier {
  private fetchFn: typeof fetch;
  private log: (line: string) => void;

  /** fetchFn is injectable so tests never touch the network. */
  constructor(
    private cfg: NtfyConfig,
    opts: { fetchFn?: typeof fetch; log?: (line: string) => void } = {},
  ) {
    this.fetchFn = opts.fetchFn ?? fetch;
    this.log = opts.log ?? (() => {});
  }

  /** Fire-and-forget publish. The returned promise ALWAYS resolves (awaitable
   *  in tests) — an unreachable ntfy server must never take an alert seam
   *  down with it. Every failure collapses to one warn line. */
  async publish(title: string, message: string, opts: NtfyPublishOpts = {}): Promise<void> {
    const url = `${this.cfg.server.replace(/\/+$/, "")}/${this.cfg.topic}`;
    const headers: Record<string, string> = { Title: title };
    if (opts.priority !== undefined) headers.Priority = String(opts.priority);
    if (this.cfg.token) headers.Authorization = `Bearer ${this.cfg.token}`;
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), TIMEOUT_MS);
    try {
      const res = await this.fetchFn(url, { method: "POST", headers, body: message, signal: ctl.signal });
      if (!res.ok) this.log(`[ntfy] publish failed: HTTP ${res.status} (${url})`);
    } catch (e) {
      this.log(`[ntfy] publish failed: ${(e as Error).message} (${url})`);
    } finally {
      clearTimeout(timer);
    }
  }
}
