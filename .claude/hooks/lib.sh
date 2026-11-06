#!/usr/bin/env bash
# Fonctions communes aux hooks de la factory. Chargé par : source "$(dirname "$0")/lib.sh"

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

# Retour NON bloquant pour Claude après un outil (PostToolUse) : le texte est ajouté à son contexte.
post_tool_context() {
  jq -n --arg msg "$1" '{hookSpecificOutput: {hookEventName: "PostToolUse", additionalContext: $msg}}'
}
