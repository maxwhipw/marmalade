// device-id.ts — the stable per-install deviceId (spec "Protocol client":
// "stable per-install deviceId (localStorage)"). Minted once, kept forever;
// it's the origin the daemon stamps and the key its seen cursors use, so it
// must survive reloads. Two browsers = two devices, exactly like two CLIs.

const KEY = "marmalade.webui.deviceId";

export function getDeviceId(): string {
  try {
    let id = localStorage.getItem(KEY);
    if (!id) {
      id = `web-${crypto.randomUUID()}`;
      localStorage.setItem(KEY, id);
    }
    return id;
  } catch {
    // Private mode with no storage: a per-session id is better than crashing.
    return `web-ephemeral-${crypto.randomUUID()}`;
  }
}
