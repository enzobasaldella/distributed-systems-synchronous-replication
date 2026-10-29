#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
pid_file="$project_dir/runtime/pids.tsv"
data_dir="$project_dir/data"

if [[ -f "$pid_file" ]]; then
  while IFS=$'\t' read -r process_name process_pid; do
    if [[ -n "${process_pid:-}" ]] && kill -0 "$process_pid" 2>/dev/null; then
      echo "Erro: finalize o sistema antes da verificacao (ainda ativo: $process_name)." >&2
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
    echo "Erro: arquivo ausente: $required_file" >&2
    exit 1
  fi
done

if ! cmp -s "$leader" "$follower"; then
  echo "FALHA: os arquivos dos servidores de dados sao diferentes." >&2
  diff -u "$leader" "$follower" || true
  exit 1
fi

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
LC_ALL=C sort -n -u "$client_one" "$client_two" > "$temporary_dir/clientes.txt"
LC_ALL=C sort -n -u "$leader" > "$temporary_dir/dados.txt"

if ! cmp -s "$temporary_dir/clientes.txt" "$temporary_dir/dados.txt"; then
  echo "FALHA: ha diferenca entre os valores distintos dos clientes e dos dados." >&2
  diff -u "$temporary_dir/clientes.txt" "$temporary_dir/dados.txt" || true
  exit 1
fi

total_generated=$(( $(wc -l < "$client_one") + $(wc -l < "$client_two") ))
total_distinct=$(wc -l < "$leader")
echo "OK: replicas identicas e todos os valores dos clientes estao armazenados."
echo "Gerados: $total_generated; distintos persistidos: $total_distinct."
