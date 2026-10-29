#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$project_dir/scripts/compile.sh"
java -ea -cp "$project_dir/build/classes:$project_dir/build/test-classes" \
  br.edu.distribuidos.TestRunner
