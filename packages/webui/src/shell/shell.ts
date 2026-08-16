// shell.ts — the ONE shell seam (spec "Shape": shell-agnostic by construction).
//
// The webui never touches an Electron/Tauri/Deno API directly. Everything a
// native shell might do — open a link in the OS browser, raise a desktop
// notification, name the platform — goes through this interface. v0 ships only
// the browser fallback; a Tauri adapter (P1) implements the SAME interface and
// is swapped in at the app root. Nothing else in the app imports a shell API,
// so choosing the shell stays deferred (spec: nothing in v0 may depend on the
// answer).

export interface Shell {
  /** Open a URL outside the app (OS browser in a native shell; a new tab in
   *  the browser fallback). */
  openExternal(url: string): void;
  /** Raise a notification (native toast in a shell; the Notifications API in
   *  the browser, permission-gated and best-effort). */
  notify(title: string, body: string): void;
  /** A human label for the running surface — shown in settings. */
  readonly platformLabel: string;
}

/** The browser fallback: the only implementation v0 ships. */
export const browserShell: Shell = {
  openExternal(url: string): void {
    // noopener/noreferrer: a model-authored artifact link must not get a
    // handle back to this window.
    window.open(url, "_blank", "noopener,noreferrer");
  },
  notify(title: string, body: string): void {
    if (!("Notification" in window)) return;
    if (Notification.permission === "granted") {
      new Notification(title, { body });
    } else if (Notification.permission !== "denied") {
      // Best-effort: ask once, notify if granted. Never block on the answer.
      void Notification.requestPermission().then((p) => {
        if (p === "granted") new Notification(title, { body });
      });
    }
  },
  platformLabel: "web",
};
