#!/usr/bin/env bash
# PostToolUse (Edit|Write) : retour technique immédiat après chaque modification.
#  - .java : compilation Maven du module backend (./mvnw, sinon mvn)
#  - .ts/.html/.scss : Prettier sur le fichier modifié
# La vérification d'architecture est faite séparément par guard-architecture.sh.
#
# Retours à Claude :
#  - erreur de compilation         → exit 2 (bloquant : Claude doit corriger)
#  - Maven indisponible / en panne → avertissement NON bloquant (additionalContext), une fois par heure au plus
set -uo pipefail
source "$(dirname "$0")/lib.sh"

file=$(norm_path "$(jq -r '.tool_input.file_path // ""')")
root=$(project_root)

[ -z "$file" ] && exit 0
[ -f "$file" ] || exit 0

# Avertissement non bloquant, limité à une fois par heure pour ne pas noyer Claude.
warn_once() {
  local key marker
  key=$(printf '%s' "$root" | cksum | cut -d' ' -f1)
  marker="${TMPDIR:-/tmp}/claude-factory-warn-$key"
  if [ -f "$marker" ] && [ -n "$(find "$marker" -mmin -60 2>/dev/null)" ]; then
    return 0
  fi
  touch "$marker" 2>/dev/null || true
  post_tool_context "$1"
}

case "$file" in
  *.java)
    backend="$root/backend"
    [ -d "$backend" ] || exit 0
    if [ -f "$backend/mvnw" ]; then
      mvn_cmd=(sh ./mvnw)
    elif command -v mvn >/dev/null 2>&1; then
      mvn_cmd=(mvn)
    else
      warn_once "CLAUDE FACTORY : ni backend/mvnw ni mvn n'est disponible, la compilation après chaque modification n'est PAS vérifiée. Compile toi-même (./mvnw -q compile) avant de déclarer la tâche terminée, et signale-le à l'humain."
      exit 0
    fi

    out=$(cd "$backend" && "${mvn_cmd[@]}" -q -o compile 2>&1)
    rc=$?
    [ $rc -eq 0 ] && exit 0

    if printf '%s' "$out" | grep -qE 'COMPILATION ERROR|\.java:\[[0-9]+'; then
      echo "La compilation échoue après la modification de ${file#"$root"/} :" >&2
      printf '%s\n' "$out" | grep -E '\.java:\[[0-9]+|ERROR\]   |symbol:|location:' | head -25 >&2
      exit 2
    fi

    # Échec qui n'est pas une erreur de code : wrapper qui ne démarre pas, réseau, dépendance absente en mode hors ligne…
    first=$(printf '%s\n' "$out" | grep -m1 -E 'ERROR|Error|error|Failed|failed' || printf '%s\n' "$out" | head -1)
    warn_once "CLAUDE FACTORY : la compilation n'a pas pu être lancée (${mvn_cmd[*]} -q -o compile) — ce n'est pas une erreur dans le code. Cause probable : Maven ou le wrapper ne démarre pas, ou une dépendance n'est pas encore téléchargée (mode hors ligne). Premier message : ${first:0:300}. Lance ./mvnw -q compile sans -o pour vérifier, et signale-le à l'humain si le problème persiste."
    exit 0 ;;
  *.ts|*.html|*.scss)
    if [ -x "$root/frontend/node_modules/.bin/prettier" ]; then
      "$root/frontend/node_modules/.bin/prettier" --write "$file" >/dev/null 2>&1 || true
    fi ;;
esac

exit 0
