#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

mkdir -p build/classes build/test-classes
mapfile -t main_sources < <(find src/main/java -name '*.java' -type f | sort)
mapfile -t test_sources < <(find src/test/java -name '*.java' -type f | sort)

if command -v javac >/dev/null 2>&1; then
  compiler=(javac -encoding UTF-8 --release 17 -Xlint:all)
elif command -v java >/dev/null 2>&1 \
    && java --list-modules 2>/dev/null | grep -q '^jdk.compiler@'; then
  # Usa o módulo compilador quando o runtime possui jdk.compiler, mas não o javac.
  compiler=(java --module jdk.compiler/com.sun.tools.javac.Main \
    -encoding UTF-8 -source 17 -target 17 -Xlint:all,-options)
else
  echo "Erro: compilador Java nao encontrado. Instale um JDK 17 ou mais recente." >&2
  exit 1
fi

"${compiler[@]}" -d build/classes "${main_sources[@]}"
if ((${#test_sources[@]} > 0)); then
  "${compiler[@]}" -cp build/classes \
    -d build/test-classes "${test_sources[@]}"
fi

echo "Compilacao concluida em build/classes."
