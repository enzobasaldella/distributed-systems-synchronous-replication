#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="$project_dir/runtime/pids.tsv"

if [[ ! -f "$pid_file" ]]; then
  echo "No process file found."
  exit 0
fi

# Stops producers first to prevent new requests during queue draining.
while IFS=$'\t' read -r process_name process_pid; do
  if [[ "$process_name" == cliente-* ]] && kill -0 "$process_pid" 2>/dev/null; then
    kill "$process_pid"
    echo "Client stopped: $process_name"
  fi
done < "$pid_file"

# Waits for clients to finish accepting the number already persisted in their logs.
for _ in {1..60}; do
  client_alive=false
  while IFS=$'\t' read -r process_name process_pid; do
    if [[ "$process_name" == cliente-* ]] && kill -0 "$process_pid" 2>/dev/null; then
      client_alive=true
    fi
  done < "$pid_file"
  if [[ "$client_alive" == false ]]; then
    break
  fi
  sleep 0.25
done

echo "Waiting for pending workers to persist their requests..."
drained=false
temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
for attempt in {1..120}; do
  if [[ -f "$project_dir/data/cliente-1.log" \
        && -f "$project_dir/data/cliente-2.log" \
        && -f "$project_dir/data/servidor-dados-1.txt" ]]; then
    LC_ALL=C sort -n -u "$project_dir/data/cliente-1.log" \
      "$project_dir/data/cliente-2.log" > "$temporary_dir/clientes.txt"
    LC_ALL=C sort -n -u "$project_dir/data/servidor-dados-1.txt" \
      > "$temporary_dir/dados.txt"
    if cmp -s "$temporary_dir/clientes.txt" "$temporary_dir/dados.txt"; then
      drained=true
      echo "Queue drained: all client values were persisted."
      break
    fi
    if ((attempt % 5 == 0)); then
      client_count=$(wc -l < "$temporary_dir/clientes.txt")
      data_count=$(wc -l < "$temporary_dir/dados.txt")
      pending=$((client_count - data_count))
      if ((pending < 0)); then
        pending=0
      fi
      echo "Still waiting for $pending distinct value(s)..."
    fi
  fi
  sleep 1
done

while IFS=$'\t' read -r process_name process_pid; do
  if [[ "$process_name" != cliente-* ]] && kill -0 "$process_pid" 2>/dev/null; then
    kill "$process_pid"
    echo "Stopped: $process_name"
  fi
done < "$pid_file"

: > "$pid_file"
echo "Every process was requested to stop."

if [[ "$drained" == false ]]; then
  echo "Warning: draining did not finish within 120 seconds; inspect the logs." >&2
  exit 1
fi
