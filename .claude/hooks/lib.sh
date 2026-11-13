#!/usr/bin/env bash
# Fonctions communes aux hooks de la factory. Chargé par : source "$(dirname "$0")/lib.sh"

# jq est indispensable : il lit le JSON envoyé par Claude Code sur l'entrée standard.
# need_jq block → hook de protection : sans jq, on BLOQUE (exit 2) plutôt que de laisser tout passer.
# need_jq skip  → hook de confort : sans jq, on ne fait rien (exit 0).
jq_install_hint() {
  echo "Installez jq : Windows « winget install jqlang.jq » · macOS « brew install jq » · Debian/Ubuntu « sudo apt install jq » · Fedora « sudo dnf install jq »."
}
need_jq() {
  command -v jq >/dev/null 2>&1 && return 0
  if [ "${1:-skip}" = "block" ]; then
    echo "Hook de protection de la factory : jq est introuvable, l'action est bloquée par sécurité." >&2
    jq_install_hint >&2
    exit 2
  fi
  exit 0
}

# Normalise un chemin pour qu'il soit comparable quel que soit l'OS :
#   C:\Users\x\f.java  →  C:/Users/x/f.java
#   /c/Users/x/f.java  →  C:/Users/x/f.java   (forme Git Bash / MSYS)
#   c:/Users/x/f.java  →  C:/Users/x/f.java
norm_path() {
  local p="${1//\\//}"
  p="${p%$'\r'}"
  if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    p="${BASH_REMATCH[1]}:/${BASH_REMATCH[2]}"
  fi
  if [[ "$p" =~ ^([a-zA-Z]):/(.*)$ ]]; then
    p="$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:lower:]' '[:upper:]'):/${BASH_REMATCH[2]}"
  fi
  printf '%s' "$p"
}

# Racine du projet, normalisée.
project_root() {
  norm_path "${CLAUDE_PROJECT_DIR:-$(pwd)}"
}

# Architecture active lue dans .claude/factory.json (vide si absent ou illisible).
active_architecture() {
  jq -r '.architecture // empty' "$(project_root)/.claude/factory.json" 2>/dev/null | tr -d '\r'
}

# Intégrations actives (.claude/stack.json), une par ligne.
active_integrations() {
  jq -r '(.integrations // [])[]' "$(project_root)/.claude/stack.json" 2>/dev/null | tr -d '\r'
}

# Segments de package d'un rôle technique dans l'architecture active (roles.conf), un par ligne.
role_segments() {
  local conf
  conf="$(project_root)/.claude/skills/architecture/references/$(active_architecture)/roles.conf"
  [ -f "$conf" ] || return 0
  tr -d '\r' < "$conf" | awk -F ' :: ' -v r="$1" '$1 == r { n = split($2, a, ","); for (i = 1; i <= n; i++) { gsub(/^ +| +$/, "", a[i]); print a[i] } }'
}

# Retour NON bloquant pour Claude après un outil (PostToolUse) : le texte est ajouté à son contexte.
post_tool_context() {
  jq -n --arg msg "$1" '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $msg}}'
}
