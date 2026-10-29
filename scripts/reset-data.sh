#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="$project_dir/runtime/pids.tsv"

if [[ -f "$pid_file" ]]; then
  while IFS=$'\t' read -r process_name process_pid; do
    if [[ -n "${process_pid:-}" ]] && kill -0 "$process_pid" 2>/dev/null; then
      echo "Error: stop $process_name before deleting generated data." >&2
      exit 1
    fi
  done < "$pid_file"
fi

mkdir -p "$project_dir/data"
find "$project_dir/data" -maxdepth 1 -type f \
  \( -name 'servidor-dados-*.txt' -o -name 'cliente-*.log' \) -delete
echo "Generated data files and client logs removed."
