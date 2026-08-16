// Adapted from Kai (github.com/SimonSchubert/Kai, Apache-2.0) —
// ui/dynamicui/KaiUiParser.kt syntax-repair stages (fixJsonSyntax /
// sanitizeJson / trimTrailingIncomplete), by way of the Android client's
// JsonRepair.kt port. See CREDITS.md.
//
// Repairs the JSON damage LLMs actually produce inside ```marmalade-ui
// fences — truncated output (mid-string, mid-key), `"key=[` for `"key":[`,
// extra/mismatched closers, an object in an array missing its `}` before
// the next `,{` — so a partial tree still renders with field defaults
// instead of degrading to a raw code block.

/** Fix common LLM JSON syntax errors like `"key=[` instead of `"key":[`. */
export function fixJsonSyntax(raw: string): string {
  return raw.replace(/"(\w+)=([{[])/g, '"$1":$2');
}

/**
 * Repair JSON with extra/mismatched closing braces/brackets using
 * stack-based matching; trim + close truncated structures. Returns the
 * input unchanged when it doesn't start with `{`/`[`.
 */
export function sanitizeJson(raw: string): string {
  if (raw.length === 0) return raw;
  if (raw[0] !== "{" && raw[0] !== "[") return raw;

  const stack: string[] = [];
  let result = "";
  let inString = false;
  let escaped = false;
  // Last structural char emitted outside strings — detects `,{` inside an
  // object whose parent is an array (a forgotten `}` between array elements).
  let lastSig = " ";

  for (const c of raw) {
    if (escaped) {
      escaped = false;
      result += c;
      continue;
    }
    if (c === "\\" && inString) {
      escaped = true;
      result += c;
      continue;
    }
    if (c === '"') {
      inString = !inString;
      result += c;
      lastSig = c;
      continue;
    }
    if (inString) {
      result += c;
      continue;
    }
    if (/\s/.test(c)) {
      result += c;
      continue;
    }
    switch (c) {
      case "{":
      case "[": {
        if (lastSig === "," && stack[stack.length - 1] === "{" && stack[stack.length - 2] === "[") {
          const commaIdx = result.lastIndexOf(",");
          if (commaIdx >= 0) {
            result = result.slice(0, commaIdx) + "}" + result.slice(commaIdx);
            stack.pop();
          }
        }
        stack.push(c);
        result += c;
        lastSig = c;
        break;
      }
      case "}":
        if (stack[stack.length - 1] === "{") {
          stack.pop();
          result += c;
          lastSig = c;
        }
        break;
      case "]":
        if (stack[stack.length - 1] === "[") {
          stack.pop();
          result += c;
          lastSig = c;
        }
        break;
      default:
        result += c;
        lastSig = c;
    }
    if (stack.length === 0) return result;
  }

  // Unclosed JSON — trim trailing incomplete content, then close what's open.
  let out = trimTrailingIncomplete(result, inString);
  for (let i = stack.length - 1; i >= 0; i--) {
    out += stack[i] === "{" ? "}" : "]";
  }
  return out;
}

/**
 * Trim trailing incomplete content from truncated JSON (half-open strings,
 * trailing commas/colons, orphaned keys) so appending closers produces
 * valid JSON.
 */
function trimTrailingIncomplete(json: string, inString: boolean): string {
  let s = json;
  if (inString) {
    const lastQuote = s.lastIndexOf('"');
    if (lastQuote >= 0) s = s.slice(0, lastQuote);
  }
  s = s.trimEnd();
  while (s.length > 0) {
    const last = s[s.length - 1];
    if (last === "," || last === ":") {
      s = s.slice(0, -1).trimEnd();
      continue;
    }
    if (last !== '"') break;
    const openQuote = s.lastIndexOf('"', s.length - 2);
    if (openQuote < 0) break;
    const before = s.slice(0, openQuote).trimEnd();
    if (before.length === 0 || [",", "{", "["].includes(before[before.length - 1])) {
      s = before;
    } else {
      break;
    }
  }
  return s;
}
