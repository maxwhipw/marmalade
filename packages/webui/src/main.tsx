// main.tsx — the SPA entry. Wires the providers (settings → gateway) around the
// App shell and picks the shell adapter (browser fallback in v0; a Tauri/
// Electron adapter swaps in here later without touching app code — spec "Shape").

import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { SettingsProvider } from "./settings/provider.js";
import { GatewayProvider } from "./app/gateway-context.js";
import { App } from "./app/App.js";
import { browserShell } from "./shell/shell.js";
import "./styles/marmalade.css";
import "./styles/app.css";

const root = document.getElementById("root");
if (!root) throw new Error("missing #root");

createRoot(root).render(
  <StrictMode>
    <SettingsProvider>
      <GatewayProvider>
        <App shell={browserShell} />
      </GatewayProvider>
    </SettingsProvider>
  </StrictMode>,
);
