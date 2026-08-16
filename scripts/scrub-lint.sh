#!/usr/bin/env bash
# scrub-lint.sh — mechanical backstop against private info reaching the public repo.
#
# Greps every git-tracked (non-binary) file for known leak classes: personal
# identifiers, private hosts/tailnet addresses, private workspace paths and
# credential shapes. Exits 1 on any violation.
#
# Placeholder convention: RFC 5737 documentation IPs (192.0.2.x) and RFC 2606
# reserved names (host.example) are the sanctioned fake values — no pattern here
# matches them, and none should ever be added.
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ ! -d .git ]]; then
  echo "scrub-lint: must be run from inside the repo (expected <repo>/scripts/scrub-lint.sh)" >&2
  exit 2
fi

ALLOW_FILE="scripts/scrub-lint-allow.txt"
# The linter's own files necessarily contain every pattern.
SELF=("scripts/scrub-lint.sh" "$ALLOW_FILE")

# label|cs|regex   — cs is "ci" for case-insensitive, "cs" for case-sensitive.
PATTERNS=(
  # personal identifiers
  'max|cs|\bMax\b'
  'maxwellw|ci|maxwellw'
  'posteo|ci|posteo'
  'tahlia|ci|Tahlia'
  'home-max|cs|/home/max'
  'nexus|cs|\.nexus'
  'agent-wiki|cs|agent-wiki'
  # private hosts / tailnet
  'tailnet-name|cs|cassowary-newton'
  'host-george|ci|\bgeorge\b'
  'host-x220|ci|\bx220\b'
  # NOTE: no pattern for generic phone-model mentions ("Pixel 8a") — hardware
  # names in benchmarks/docs are fine; the device's private address is what
  # leaks, and that's covered by the ip-* patterns below.
  'ts-net|cs|\.ts\.net'
  # known-real addresses
  'ip-marmalade-ts|cs|100\.99\.77\.61'
  'ip-pixel-ts|cs|100\.114\.195\.29'
  'ip-george-ts|cs|100\.122\.135\.94'
  'ip-marmalade-lan|cs|10\.0\.0\.11\b'
  'ip-george-lan|cs|10\.0\.0\.128\b'
  # private workspace paths
  # NOTE: neutral fixture paths like /home/user/coding are sanctioned; real
  # personal paths are caught by home-max above.
  'path-coding-tilde|cs|~/coding/'
  # credential shapes
  'private-key|cs|-----BEGIN [A-Z ]*PRIVATE KEY'
  'gh-token|cs|ghp_[A-Za-z0-9]{20,}'
  'gh-pat|cs|github_pat_[A-Za-z0-9_]{20,}'
  'anthropic-key|cs|sk-ant-[A-Za-z0-9-]{10,}'
  'aws-key|cs|AKIA[0-9A-Z]{16}'
  'slack-token|cs|xox[abpr]-'
  'age-key|cs|AGE-SECRET-KEY-1'
  'jwt|cs|eyJhbGciOi'
)

# --- allowlist: "<path>:<pattern-label>" per line, '#' comments allowed -------
declare -A ALLOW=()
if [[ -f $ALLOW_FILE ]]; then
  while IFS= read -r entry; do
    entry="${entry%%#*}"
    entry="$(printf '%s' "$entry" | tr -d '[:space:]')"
    [[ -z $entry ]] && continue
    ALLOW["$entry"]=1
  done <"$ALLOW_FILE"
fi

# --- file list ---------------------------------------------------------------
mapfile -t FILES < <(git ls-files | grep -vxF -e "${SELF[0]}" -e "${SELF[1]}" || true)
if [[ ${#FILES[@]} -eq 0 ]]; then
  echo "scrub-lint: no tracked files to scan" >&2
  exit 2
fi

# grep -I silently skips files it classifies as binary — a source file with a
# stray NUL byte (e.g. transcript-cache.ts uses one as a key separator) would
# evade the scan entirely. Rescan text-extension files containing NULs in
# forced-text mode (-a).
TEXT_EXT_RE='\.(ts|tsx|js|jsx|mjs|kt|kts|java|md|json|jsonc|sh|yml|yaml|xml|txt|html|css|sql|py|gradle|properties|toml)$'
mapfile -t NUL_FILES < <(printf '%s\n' "${FILES[@]}" | grep -E "$TEXT_EXT_RE" | while IFS= read -r f; do
  # (grep -P '\x00' does not match NUL; byte-compare after stripping NULs does)
  if ! LC_ALL=C tr -d '\0' <"$f" | cmp -s - "$f"; then printf '%s\n' "$f"; fi
done)

declare -A HITS=() ALLOWED=()
violations=0
allowed_total=0

for spec in "${PATTERNS[@]}"; do
  label="${spec%%|*}"
  rest="${spec#*|}"
  case_mode="${rest%%|*}"
  pattern="${rest#*|}"

  gflags=(-E -n -I -H)
  [[ $case_mode == ci ]] && gflags+=(-i)
  aflags=(-E -n -a -H)
  [[ $case_mode == ci ]] && aflags+=(-i)

  while IFS= read -r hit; do
    [[ -z $hit ]] && continue
    path="${hit%%:*}"
    rem="${hit#*:}"
    lineno="${rem%%:*}"
    text="${rem#*:}"
    text="${text#"${text%%[![:space:]]*}"}"      # ltrim
    [[ ${#text} -gt 120 ]] && text="${text:0:120}…"

    if [[ -n ${ALLOW["$path:$label"]:-} ]]; then
      ALLOWED["$label"]=$(( ${ALLOWED["$label"]:-0} + 1 ))
      allowed_total=$(( allowed_total + 1 ))
      continue
    fi
    printf '%s:%s: [%s] %s\n' "$path" "$lineno" "$label" "$text"
    HITS["$label"]=$(( ${HITS["$label"]:-0} + 1 ))
    violations=$(( violations + 1 ))
  done < <(
    printf '%s\0' "${FILES[@]}" | xargs -0 grep "${gflags[@]}" -e "$pattern" -- 2>/dev/null || true
    if [[ ${#NUL_FILES[@]} -gt 0 ]]; then
      printf '%s\0' "${NUL_FILES[@]}" | xargs -0 grep "${aflags[@]}" -e "$pattern" -- 2>/dev/null | tr -d '\0' || true
    fi
  )
done

# --- summary -----------------------------------------------------------------
echo
echo "scrub-lint: scanned ${#FILES[@]} tracked files, ${#PATTERNS[@]} patterns"
if [[ ${#NUL_FILES[@]} -gt 0 ]]; then
  echo "note: ${#NUL_FILES[@]} NUL-containing text file(s) rescanned in forced-text mode: ${NUL_FILES[*]}"
fi
if [[ $allowed_total -gt 0 ]]; then
  echo "allowlisted matches ($allowed_total total — watch for silent growth):"
  for label in "${!ALLOWED[@]}"; do printf '  %-20s %s\n' "$label" "${ALLOWED[$label]}"; done | sort
fi
if [[ $violations -eq 0 ]]; then
  echo "result: clean (0 violations)"
  exit 0
fi
echo "violations by label:"
for label in "${!HITS[@]}"; do printf '  %-20s %s\n' "$label" "${HITS[$label]}"; done | sort
echo "result: FAIL ($violations violations)"
exit 1
