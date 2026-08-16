// CronView.tsx — scheduled prompts (cron) management. Daemon semantics the UI
// must respect (test/cron-router.test.ts is the spec): the list includes
// disabled jobs (a silently-skipped job is how a dead job goes unnoticed —
// their disable REASON rides last_error), one-shots self-disable after firing,
// run-now is out-of-band (next_run_at doesn't move), and enable/disable is
// cron.update {enabled}. All state lives daemon-side; every mutation refetches.

import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { CronJobWire } from "@marmalade/protocol";
import { useGateway } from "../app/gateway-context.js";
import {
  buildSchedule,
  describeSchedule,
  jobStateLabel,
  lastRunLabel,
} from "../components/cron-format.js";

export function CronView(): ReactNode {
  const { client, status, sessions } = useGateway();
  const [jobs, setJobs] = useState<CronJobWire[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // A ticking now keeps the countdowns honest without refetching.
  const [now, setNow] = useState(() => Date.now());

  const refresh = useCallback(() => {
    client.cronList().then(
      (rows) => { setJobs(rows); setError(null); },
      (e: Error) => setError(e.message),
    );
  }, [client]);

  useEffect(() => {
    if (status === "connected") refresh();
  }, [status, refresh]);

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);

  const mutate = (op: Promise<unknown>, doneMsg?: string) => {
    setNotice(null);
    op.then(
      () => { if (doneMsg) setNotice(doneMsg); refresh(); },
      (e: Error) => setError(e.message),
    );
  };

  return (
    <div className="mm-settings">
      <CreateJobCard onCreated={refresh} onError={setError} />

      <section className="mm-card">
        <div className="mm-row" style={{ justifyContent: "space-between" }}>
          <h2 style={{ margin: 0 }}>Scheduled prompts</h2>
          <button className="mm-btn ghost small" onClick={refresh}>Refresh</button>
        </div>
        {error && <p className="mm-hint" style={{ color: "var(--error)" }}>{error}</p>}
        {notice && <p className="mm-hint">{notice}</p>}
        {jobs === null && !error && <p className="mm-hint">Loading…</p>}
        {jobs?.length === 0 && <p className="mm-hint">No scheduled prompts yet — create one above.</p>}
        {jobs?.map((j) => (
          <div key={j.job_id} className="mm-cron-job" data-enabled={j.enabled}>
            <div className="mm-row" style={{ justifyContent: "space-between", gap: 8 }}>
              <strong>{j.name ?? "(unnamed)"}</strong>
              <span className="mm-rail-sub">{describeSchedule(j.schedule)}</span>
            </div>
            <div className="mm-rail-sub">{jobStateLabel(j, now)}</div>
            <div
              className="mm-rail-sub"
              style={j.last_status === "error" ? { color: "var(--error)" } : undefined}
            >
              {lastRunLabel(j)}
            </div>
            <div className="mm-rail-sub" title={j.prompt}>
              → {sessionLabel(sessions, j.session_id)}: {truncate(j.prompt, 120)}
            </div>
            <div className="mm-row" style={{ marginTop: 6 }}>
              <button
                className="mm-btn outline small"
                onClick={() => mutate(client.cronUpdate({ job_id: j.job_id, enabled: !j.enabled }))}
              >
                {j.enabled ? "Disable" : "Enable"}
              </button>
              <button
                className="mm-btn outline small"
                onClick={() =>
                  mutate(
                    client.cronRunNow(j.job_id).then((fired) => {
                      if (!fired) throw new Error("job is mid-run — skipped (single-flight)");
                    }),
                    "Fired.",
                  )
                }
              >
                Run now
              </button>
              <button
                className="mm-btn ghost small"
                onClick={() => {
                  if (confirm(`Delete "${j.name ?? j.job_id}"? This cannot be undone.`)) {
                    mutate(client.cronDelete(j.job_id));
                  }
                }}
              >
                Delete
              </button>
            </div>
          </div>
        ))}
      </section>
    </div>
  );
}

function sessionLabel(sessions: { session_id: string; title?: string }[], id: string): string {
  const s = sessions.find((r) => r.session_id === id);
  return s?.title || id.slice(0, 12);
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + "…" : s;
}

type Kind = "cron" | "every" | "at";

function CreateJobCard({ onCreated, onError }: { onCreated: () => void; onError: (m: string) => void }): ReactNode {
  const { client, sessions } = useGateway();
  const [kind, setKind] = useState<Kind>("every");
  const [name, setName] = useState("");
  const [prompt, setPrompt] = useState("");
  const [sessionId, setSessionId] = useState("");
  const [expr, setExpr] = useState("");
  const [tz, setTz] = useState("");
  const [every, setEvery] = useState("1h");
  const [at, setAt] = useState("");
  const [busy, setBusy] = useState(false);

  // session.list is last_active DESC — default the target to the most recent
  // session (same default as the CLI's --session last).
  const effectiveSession = sessionId || sessions[0]?.session_id || "";

  const create = async () => {
    if (busy) return;
    try {
      if (!prompt.trim()) throw new Error("a prompt is required");
      if (!effectiveSession) throw new Error("no sessions exist — open a chat first");
      const schedule = buildSchedule(kind, { expr, tz, every, at }, Date.now());
      setBusy(true);
      await client.cronCreate({
        session_id: effectiveSession,
        prompt: prompt.trim(),
        schedule,
        ...(name.trim() ? { name: name.trim() } : {}),
      });
      setPrompt("");
      setName("");
      onCreated();
    } catch (e) {
      onError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="mm-card">
      <h2>New scheduled prompt</h2>
      <div className="mm-field">
        <label htmlFor="cron-name">Name (optional)</label>
        <input id="cron-name" type="text" value={name} maxLength={120} placeholder="e.g. morning briefing" onChange={(e) => setName(e.target.value)} />
      </div>
      <div className="mm-field">
        <label htmlFor="cron-prompt">Prompt</label>
        <textarea
          id="cron-prompt"
          value={prompt}
          rows={3}
          placeholder="What should the agent be asked?"
          onChange={(e) => setPrompt(e.target.value)}
        />
      </div>
      <div className="mm-field">
        <label htmlFor="cron-session">Target session</label>
        <select id="cron-session" value={effectiveSession} onChange={(e) => setSessionId(e.target.value)}>
          {sessions.map((s) => (
            <option key={s.session_id} value={s.session_id}>
              {s.title || s.session_id.slice(0, 12)}
            </option>
          ))}
        </select>
      </div>
      <div className="mm-field">
        <label>Schedule</label>
        <div className="mm-chips">
          {(
            [
              ["every", "Repeat every"],
              ["cron", "Cron expression"],
              ["at", "Once at"],
            ] as const
          ).map(([k, label]) => (
            <button key={k} className="mm-chip" aria-pressed={kind === k} onClick={() => setKind(k)}>
              {label}
            </button>
          ))}
        </div>
      </div>
      {kind === "every" && (
        <div className="mm-field">
          <label htmlFor="cron-every">Interval (30s, 15m, 2h, 1d — bare numbers are minutes)</label>
          <input id="cron-every" type="text" value={every} onChange={(e) => setEvery(e.target.value)} />
        </div>
      )}
      {kind === "cron" && (
        <>
          <div className="mm-field">
            <label htmlFor="cron-expr">Cron expression (5-field; 6-field with seconds also works)</label>
            <input id="cron-expr" type="text" value={expr} placeholder="0 9 * * 1-5" onChange={(e) => setExpr(e.target.value)} />
          </div>
          <div className="mm-field">
            <label htmlFor="cron-tz">Timezone (IANA, blank = daemon host)</label>
            <input id="cron-tz" type="text" value={tz} placeholder="e.g. Australia/Brisbane" onChange={(e) => setTz(e.target.value)} />
          </div>
        </>
      )}
      {kind === "at" && (
        <div className="mm-field">
          <label htmlFor="cron-at">Fire once at (fires, then the job disables itself)</label>
          <input id="cron-at" type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} />
        </div>
      )}
      <div className="mm-row">
        <button className="mm-btn accent" disabled={busy} onClick={() => void create()}>
          {busy ? "Creating…" : "Create"}
        </button>
      </div>
    </section>
  );
}
