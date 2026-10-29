#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="$project_dir/runtime/pids.tsv"
data_dir="$project_dir/data"

if [[ -f "$pid_file" ]]; then
  while IFS=$'\t' read -r process_name process_pid; do
    if [[ -n "${process_pid:-}" ]] && kill -0 "$process_pid" 2>/dev/null; then
      echo "Error: stop the system before verification (still active: $process_name)." >&2
      exit 1
    fi
  done < "$pid_file"
fi

leader="$data_dir/servidor-dados-1.txt"
follower="$data_dir/servidor-dados-2.txt"
client_one="$data_dir/cliente-1.log"
client_two="$data_dir/cliente-2.log"
for required_file in "$leader" "$follower" "$client_one" "$client_two"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Error: required file is missing: $required_file" >&2
    exit 1
  fi
done

if ! cmp -s "$leader" "$follower"; then
  echo "FAIL: data-server files differ." >&2
  diff -u "$leader" "$follower" || true
  exit 1
fi

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
LC_ALL=C sort -n -u "$client_one" "$client_two" > "$temporary_dir/clientes.txt"
LC_ALL=C sort -n -u "$leader" > "$temporary_dir/dados.txt"

if ! cmp -s "$temporary_dir/clientes.txt" "$temporary_dir/dados.txt"; then
  echo "FAIL: distinct client values differ from persisted data." >&2
  diff -u "$temporary_dir/clientes.txt" "$temporary_dir/dados.txt" || true
  exit 1
fi

total_generated=$(( $(wc -l < "$client_one") + $(wc -l < "$client_two") ))
total_distinct=$(wc -l < "$leader")
echo "OK: replicas are identical and every client value is stored."
echo "Generated: $total_generated; distinct persisted: $total_distinct."
