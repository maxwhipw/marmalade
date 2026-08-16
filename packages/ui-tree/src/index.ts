export * from "./nodes.js";
export { parseUiTree, pressedMessage, respondedMessage, callbackMessage } from "./parser.js";
export { fixJsonSyntax, sanitizeJson } from "./repair.js";

/** The fence language that carries a Marmalade UI v1 tree. */
export const UI_FENCE_LANG = "marmalade-ui";
