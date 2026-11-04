#!/usr/bin/env bash
# PostToolUse (Edit|Write) : retour immédiat à Claude après chaque modification.
#  1. .java dans domain/ ou application/ : vérifie la règle de dépendance de l'architecture hexagonale
#  2. .java : compilation Maven du module backend
#  3. .ts/.html/.scss : Prettier sur le fichier modifié
# exit 2 = le message (stderr) est renvoyé à Claude, qui corrige dans la foulée.
set -uo pipefail

file=$(jq -r '.tool_input.file_path // ""')
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"

[ -z "$file" ] && exit 0
[ -f "$file" ] || exit 0

case "$file" in
  *.java)
    # --- 1. Règle de dépendance (contrôle rapide ; ArchitectureTest reste la référence) ---
    if [[ "$file" == */src/main/java/*/domain/* ]]; then
      bad=$(grep -nE '^import (static )?(org\.springframework|jakarta\.persistence|jakarta\.validation|lombok|[a-z0-9_.]+\.(application|adapter|config)\.)' "$file" || true)
      if [ -n "$bad" ]; then
        echo "Violation de l'architecture hexagonale dans $file :" >&2
        echo "le domaine doit rester en Java pur (pas de Spring, JPA, Jakarta Validation, Lombok, ni d'import application/adapter/config)." >&2
        echo "$bad" >&2
        exit 2
      fi
    fi
    if [[ "$file" == */src/main/java/*/application/* ]]; then
      bad=$(grep -nE '^import (static )?(jakarta\.persistence|org\.springframework\.(web|data)|[a-z0-9_.]+\.(adapter|config)\.)' "$file" || true)
      if [ -n "$bad" ]; then
        echo "Violation de l'architecture hexagonale dans $file :" >&2
        echo "l'application ne dépend que du domaine et de ses ports (pas d'adapter, de config, de JPA ni de Spring Web/Data)." >&2
        echo "$bad" >&2
        exit 2
      fi
    fi

    # --- 2. Compilation ---
    if [ -x "$root/backend/mvnw" ]; then
      out=$(cd "$root/backend" && ./mvnw -q -o compile 2>&1) || {
        echo "La compilation échoue après la modification de $file :" >&2
        echo "$out" | grep -E "ERROR|error:" | head -20 >&2
        exit 2
      }
    fi ;;
  *.ts|*.html|*.scss)
    if [ -x "$root/frontend/node_modules/.bin/prettier" ]; then
      "$root/frontend/node_modules/.bin/prettier" --write "$file" >/dev/null 2>&1 || true
    fi ;;
esac

exit 0
