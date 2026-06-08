#!/usr/bin/env bash
set -euo pipefail

DB_NAME="${DB_NAME:-tpch_cs}"
DB_PORT="${DB_PORT:-40040}"
GSQL_BIN="${GSQL_BIN:-gsql}"
QUERY_DIR="${QUERY_DIR:-presto-main-base/src/main/resources/test-query}"
OUTPUT_DIR="${OUTPUT_DIR:-tpch_explain_json}"

usage() {
  cat <<'EOF'
Usage:
  ./run_tpch_explain_json.sh [options]

Environment variables:
  DB_NAME     Database name to connect to (default: tpch_cs)
  DB_PORT     gsql port (default: 40040)
  GSQL_BIN    gsql binary path/name (default: gsql)
  QUERY_DIR   Directory containing the 22 SQL files (default: presto-main-base/src/main/resources/test-query)
  OUTPUT_DIR  Directory where outputs will be written (default: tpch_explain_json)

What it does:
  - Finds all q*_r1.sql files in QUERY_DIR
  - Removes an existing leading "explain analyze" if present
  - Runs: explain (verbose, format json) <query>
  - Saves one .json file per query and a combined log
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if ! command -v "$GSQL_BIN" >/dev/null 2>&1; then
  echo "Error: gsql binary not found: $GSQL_BIN" >&2
  exit 1
fi

if [[ ! -d "$QUERY_DIR" ]]; then
  echo "Error: query directory not found: $QUERY_DIR" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

QUERY_FILES=()
for f in "$QUERY_DIR"/q*_r1.sql; do
  [[ -f "$f" ]] && QUERY_FILES+=("$f")
done
IFS=$'\n' QUERY_FILES=($(printf '%s\n' "${QUERY_FILES[@]}" | sort -V))
unset IFS

if [[ "${#QUERY_FILES[@]}" -ne 22 ]]; then
  echo "Warning: expected 22 query files, found ${#QUERY_FILES[@]}" >&2
fi

for query_file in "${QUERY_FILES[@]}"; do
  query_name="$(basename "$query_file" .sql)"
  output_file="$OUTPUT_DIR/${query_name}.json"
  log_file="$OUTPUT_DIR/${query_name}.log"

  query_sql="$(python3 - "$query_file" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding='utf-8')
lines = text.splitlines()

# Remove leading comments/blank lines, then a single leading "explain analyze" if present.
idx = 0
while idx < len(lines) and not lines[idx].strip():
    idx += 1
while idx < len(lines) and lines[idx].lstrip().startswith('--'):
    idx += 1
while idx < len(lines) and not lines[idx].strip():
    idx += 1

if idx < len(lines) and re.match(r'^\s*explain\s+analyze\b', lines[idx], re.IGNORECASE):
    lines = lines[:idx] + lines[idx + 1:]

sql = '\n'.join(lines).strip()
print(sql)
PY
)"

  {
    printf '%s\n' "\\timing on"
    printf '%s\n' "\\o $output_file"
    printf '%s\n' "explain (verbose, format json)"
    printf '%s\n' "$query_sql"
    printf '%s\n' ";"
    printf '%s\n' "\\o"
  } | "$GSQL_BIN" -d "$DB_NAME" -p "$DB_PORT" >"$log_file" 2>&1

  echo "Wrote $output_file"
done

echo "Done. Results are in: $OUTPUT_DIR"
