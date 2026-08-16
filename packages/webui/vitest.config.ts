// Vitest config for the webui.
//
// The v0 suite is the gateway client's digital-twin (test/gateway-client.test.ts),
// which is pure TS over a scripted fake socket — no DOM. So the environment is
// "node": no jsdom dependency to carry, and the tests mirror the daemon's
// node:test replay fixtures (packages/daemon/test) rather than fighting jsdom.

import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["test/**/*.test.ts"],
  },
});
