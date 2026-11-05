#!/usr/bin/env bash
# PostToolUse (Edit|Write) : retour technique immédiat après chaque modification.
#  - .java : compilation Maven du module backend
#  - .ts/.html/.scss : Prettier sur le fichier modifié
# La vérification d'architecture est faite séparément par guard-architecture.sh.
# exit 2 = le message (stderr) est renvoyé à Claude, qui corrige dans la foulée.
set -uo pipefail

file=$(jq -r '.tool_input.file_path // ""' | tr -d '\r')
file="${file//\\//}"
root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
root="${root//\\//}"

[ -z "$file" ] && exit 0
[ -f "$file" ] || exit 0

case "$file" in
  *.java)
    if [ -f "$root/backend/mvnw" ]; then
      out=$(cd "$root/backend" && sh ./mvnw -q -o compile 2>&1) || {
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
