// ClarifySheet.tsx — the agent-question sheet (clarify round-trip).
//
// The daemon parks an AskUserQuestion tool call and broadcasts clarify.request
// with 1–4 structured questions (2–4 options each, optional multi-select).
// This sheet stages a selection per question and answers via clarify.respond:
// answers maps question text → chosen answer (multi-select comma-joined), or a
// freeform response typed instead of picking. Dismissing sends neither — the
// daemon tells the agent to proceed on its own judgment (the run survives).
// clarify.resolved (any device answered) clears the sheet everywhere.

import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useGateway } from "../app/gateway-context.js";

interface ClarifyOption {
  label: string;
  description: string;
}

interface ClarifyQuestion {
  question: string;
  header: string;
  options: ClarifyOption[];
  multi_select: boolean;
}

interface ClarifyRequest {
  session_id: string;
  request_id?: string;
  questions: ClarifyQuestion[];
}

export function ClarifySheet(): ReactNode {
  const { client } = useGateway();
  const [pending, setPending] = useState<ClarifyRequest | null>(null);
  // question text → set of selected labels (staged until Answer).
  const [picks, setPicks] = useState<Record<string, string[]>>({});
  const [freeText, setFreeText] = useState("");

  useEffect(() => {
    const off = client.on("frame", (frame) => {
      const f = frame as { method?: string; params?: { type?: string; payload?: ClarifyRequest; session_id?: string } };
      if (f.method !== "event") return;
      const { type, payload, session_id } = f.params ?? {};
      if (type === "clarify.request") {
        setPending({
          questions: [],
          ...(payload ?? {}),
          session_id: session_id ?? payload?.session_id ?? "",
        });
        setPicks({});
        setFreeText("");
      } else if (type === "clarify.resolved") {
        setPending(null);
      }
    });
    return off;
  }, [client]);

  if (!pending) return null;

  const toggle = (q: ClarifyQuestion, label: string) => {
    setPicks((prev) => {
      const cur = prev[q.question] ?? [];
      const next = q.multi_select
        ? (cur.includes(label) ? cur.filter((l) => l !== label) : [...cur, label])
        : [label];
      return { ...prev, [q.question]: next };
    });
  };

  const answered = Object.values(picks).some((v) => v.length > 0) || freeText.trim() !== "";

  const submit = (dismiss: boolean) => {
    if (dismiss) {
      void client.submitClarifyResponse(pending.session_id, pending.request_id);
    } else {
      const answers: Record<string, string> = {};
      for (const [question, labels] of Object.entries(picks)) {
        if (labels.length) answers[question] = labels.join(", ");
      }
      void client.submitClarifyResponse(pending.session_id, pending.request_id, answers, freeText.trim() || undefined);
    }
    setPending(null);
  };

  return (
    <div className="mm-gated" role="dialog" aria-label="the agent has a question" style={{ position: "fixed", inset: "auto 24px 24px auto", maxWidth: 420, background: "var(--surface-raised)", borderRadius: "var(--radius-box)", boxShadow: "0 8px 32px rgba(0,0,0,0.25)" }}>
      <h2>The agent has a question</h2>
      {pending.questions.map((q) => (
        <div key={q.question} style={{ marginBottom: 12 }}>
          {q.header && <span className="mm-chip">{q.header}</span>}
          <p>{q.question}</p>
          <div className="mm-row" style={{ flexWrap: "wrap", gap: 8 }}>
            {q.options.map((o) => {
              const selected = (picks[q.question] ?? []).includes(o.label);
              return (
                <button
                  key={o.label}
                  className={selected ? "mm-btn accent" : "mm-btn outline"}
                  title={o.description}
                  onClick={() => toggle(q, o.label)}
                >
                  {o.label}
                </button>
              );
            })}
          </div>
        </div>
      ))}
      <input
        className="mm-input"
        placeholder="Or type your own answer…"
        value={freeText}
        onChange={(e) => setFreeText(e.target.value)}
        style={{ width: "100%", marginBottom: 8 }}
      />
      <div className="mm-row">
        <button className="mm-btn accent" disabled={!answered} onClick={() => submit(false)}>Answer</button>
        <button className="mm-btn outline" onClick={() => submit(true)}>Dismiss</button>
      </div>
    </div>
  );
}
