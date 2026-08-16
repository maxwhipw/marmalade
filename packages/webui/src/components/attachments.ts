// attachments.ts — pure helpers for the composer's staged attachments.
// Uploads are LAZY: chips are local File objects until send, when each is
// uploaded (image.attach_bytes / file.attach) against the (possibly
// just-created) session — so image.detach is never needed for a chip removed
// before send, and a new conversation can stage files before its session
// exists. Behavior parity target: the Android client's OutboxDrainer
// (buildSubmitText) and desktop's use-prompt-actions.ts.

/** A file staged in the composer, not yet uploaded. */
export interface StagedAttachment {
  id: string;
  file: File;
  kind: "image" | "file";
}

let nextStageId = 1;

export function stageFile(file: File): StagedAttachment {
  return {
    id: `stage-${nextStageId++}`,
    file,
    kind: file.type.startsWith("image/") ? "image" : "file",
  };
}

/** Sent when the user attaches image(s) but types nothing (Android/desktop
 *  parity — OutboxDrainer.IMAGE_ONLY_FALLBACK_PROMPT). */
export const IMAGE_ONLY_FALLBACK_PROMPT = "What do you see in this image?";

/**
 * Final prompt text: file `@file:` refs first, then the visible text,
 * double-newline separated; image-only fallback when nothing else remains.
 * Refs already present in the text are not prepended again (retry safety).
 */
export function buildSubmitText(text: string, refTexts: string[], hasImages: boolean): string {
  const refs = refTexts.filter((r) => r.trim() !== "" && !text.includes(r));
  const parts = [...refs, ...(text.trim() ? [text] : [])];
  const combined = parts.join("\n\n");
  if (combined.trim() !== "") return combined;
  return hasImages ? IMAGE_ONLY_FALLBACK_PROMPT : combined;
}

/** File → base64 payload (no data-URL prefix) for image.attach_bytes. */
export function fileToBase64(file: File): Promise<string> {
  return fileToDataUrl(file).then((url) => url.slice(url.indexOf(",") + 1));
}

/** File → full data: URL for file.attach. */
export function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result as string);
    r.onerror = () => reject(new Error(`could not read ${file.name}`));
    r.readAsDataURL(file);
  });
}
