// Icon.tsx — the webui's one wrapper around @marmalade/icons.
//
// The map hands out glyphs on Lucide's 24×24 / 2px-round-stroke grid; this puts
// them in the shared <svg> shell. stroke="currentColor" is the whole point: an
// icon inherits the theme's ink in both modes, which is exactly what the emoji
// it replaces could not do.
//
// Shapes are rendered as real elements (never injected markup) — the map ships
// them pre-parsed, with SVG-native attribute names that pass straight to JSX.

import { createElement, type ReactNode } from "react";
import { ICONS, type IconToken } from "@marmalade/icons";

export function Icon({
  token,
  size = 16,
  className,
  title,
}: {
  token: IconToken;
  /** px. Nothing renders below 14 — below that the 2px stroke eats its counters. */
  size?: number;
  className?: string;
  title?: string;
}): ReactNode {
  return (
    <svg
      className={className ? `mm-icon ${className}` : "mm-icon"}
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      {title ? <title>{title}</title> : null}
      {ICONS[token].shapes.map((shape, i) => createElement(shape.tag, { key: i, ...shape.attrs }))}
    </svg>
  );
}
