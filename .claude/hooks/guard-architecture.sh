#!/usr/bin/env bash
# PostToolUse (Edit|Write) : vérifie la règle de dépendance de l'architecture ACTIVE après chaque écriture d'un .java.
# Moteur générique : les règles viennent de .claude/skills/architecture/references/<architecture>/rules.conf.
# exit 2 = le message (stderr) est renvoyé à Claude, qui corrige dans la foulée.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
need_jq block

file=$(norm_path "$(jq -r '.tool_input.file_path // ""')")
root=$(project_root)

[[ "$file" == *.java ]] || exit 0
[[ "$file" == */src/main/java/* ]] || exit 0
[ -f "$file" ] || exit 0

arch=$(active_architecture)
rules="$root/.claude/skills/architecture/references/$arch/rules.conf"
[ -n "$arch" ] && [ -f "$rules" ] || exit 0

rel="${file#*/src/main/java/}"             # chemin à partir du package racine
shown="${file#"$root"/}"                   # chemin affiché, relatif au projet si possible
violations=""

while IFS= read -r line || [ -n "$line" ]; do
  line="${line%$'\r'}"
  [[ -z "$line" || "$line" == \#* ]] && continue
  segment="${line%% :: *}"
  rest="${line#* :: }"
  regex="${rest%% :: *}"
  message="${rest#* :: }"
  [[ "/$rel" == */"$segment"/* ]] || continue
  bad=$(tr -d '\r' < "$file" | grep -nE "$regex" || true)
  if [ -n "$bad" ]; then
    violations+="• couche « $segment » : $message"$'\n'"$bad"$'\n'
  fi
done < "$rules"

if [ -n "$violations" ]; then
  echo "Violation de l'architecture « $arch » dans $shown :" >&2
  printf '%s' "$violations" >&2
  echo "Corrige le code pour respecter .claude/skills/architecture/references/$arch/REFERENCE.md." >&2
  exit 2
fi
exit 0
