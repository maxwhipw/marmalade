// Vite build for the marmalade webui SPA.
//
// The webui is shell-agnostic (spec "Shape"): no Electron/Tauri config here —
// a future shell adapter loads the same static bundle in its WebView. `base`
// is relative ("./") so the bundle works from a file:// WebView or any mount
// path a shell chooses, not just a server root.

import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "./",
  plugins: [react()],
  server: {
    // Loopback only — the daemon is a separate localhost process; the dev
    // server never needs to be reachable off-box.
    host: "127.0.0.1",
    port: 9131,
  },
  build: {
    target: "es2022",
    outDir: "dist",
    sourcemap: true,
  },
});
