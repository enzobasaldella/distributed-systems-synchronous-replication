#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runtime_dir="$project_dir/runtime"
pid_file="$runtime_dir/pids.tsv"
log_dir="$runtime_dir/logs"
data_dir="$project_dir/data"

"$project_dir/scripts/compile.sh"
mkdir -p "$runtime_dir" "$log_dir" "$data_dir"

if [[ -f "$pid_file" ]]; then
  while IFS=$'\t' read -r process_name process_pid; do
    if [[ -n "${process_pid:-}" ]] && kill -0 "$process_pid" 2>/dev/null; then
      echo "Erro: $process_name (PID $process_pid) ainda esta em execucao." >&2
      echo "Execute scripts/stop-all.sh antes de iniciar novamente." >&2
      exit 1
    fi
  done < "$pid_file"
fi

: > "$pid_file"

start_process() {
  local process_name="$1"
  local main_class="$2"
  shift 2
  nohup java -cp "$project_dir/build/classes" "$main_class" "$@" \
    > "$log_dir/$process_name.log" 2>&1 &
  local process_pid=$!
  printf '%s\t%s\n' "$process_name" "$process_pid" >> "$pid_file"
  echo "Iniciado: $process_name (PID $process_pid)"
}

# Inicia o seguidor antes da sincronização obrigatória do líder.
start_process dados-2 br.edu.distribuidos.data.DataServer \
  dados-2 follower 7002 7102 "$data_dir/servidor-dados-2.txt" localhost:7001
start_process dados-1 br.edu.distribuidos.data.DataServer \
  dados-1 leader 7001 0 "$data_dir/servidor-dados-1.txt" localhost:7102
sleep 1

start_process aplicacao-1 br.edu.distribuidos.application.ApplicationServer \
  aplicacao-1 6001 localhost:7001,localhost:7002
start_process aplicacao-2 br.edu.distribuidos.application.ApplicationServer \
  aplicacao-2 6002 localhost:7001,localhost:7002
start_process aplicacao-3 br.edu.distribuidos.application.ApplicationServer \
  aplicacao-3 6003 localhost:7001,localhost:7002
start_process balanceador br.edu.distribuidos.loadbalancer.LoadBalancer \
  5000 localhost:6001,localhost:6002,localhost:6003
sleep 1

start_process cliente-1 br.edu.distribuidos.client.Client \
  cliente-1 localhost 5000 "$data_dir/cliente-1.log"
start_process cliente-2 br.edu.distribuidos.client.Client \
  cliente-2 localhost 5000 "$data_dir/cliente-2.log"

echo
echo "Sistema iniciado. Logs dos processos: $log_dir"
echo "Acompanhe, por exemplo: tail -f '$log_dir/aplicacao-1.log'"
echo "Finalize com: scripts/stop-all.sh"
