// socket.ts — the transport abstraction the gateway client drives.
//
// The client's logic (ack binding, watermark dedup, replay/live dispatch,
// reconnect-resubscribe) is independent of WHICH WebSocket it drives. The
// digital-twin tests (test/gateway-client.test.ts) supply a scripted fake that
// implements this interface, mirroring the daemon test's `ws: { send }` doubles
// (packages/daemon/test/subscribe.test.ts). The browser impl wraps the real
// global WebSocket.

/** The minimal socket surface the client needs. Mirrors the browser
 *  WebSocket's event model without depending on the DOM lib, so the same code
 *  runs under vitest's node environment. */
export interface GatewaySocket {
  send(data: string): void;
  close(): void;
  onOpen: (() => void) | null;
  onClose: (() => void) | null;
  onError: (() => void) | null;
  onMessage: ((data: string) => void) | null;
}

/** Opens a socket to `url`. Injected so tests substitute a scripted twin. */
export type SocketFactory = (url: string) => GatewaySocket;

/** The production factory: wraps the browser's global WebSocket. */
export const browserSocketFactory: SocketFactory = (url) => {
  const ws = new WebSocket(url);
  const socket: GatewaySocket = {
    send: (data) => ws.send(data),
    close: () => ws.close(),
    onOpen: null,
    onClose: null,
    onError: null,
    onMessage: null,
  };
  ws.onopen = () => socket.onOpen?.();
  ws.onclose = () => socket.onClose?.();
  ws.onerror = () => socket.onError?.();
  ws.onmessage = (ev) => socket.onMessage?.(String(ev.data));
  return socket;
};
