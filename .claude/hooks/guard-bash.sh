#!/usr/bin/env bash
# PreToolUse (Bash) : bloque les commandes dangereuses avant leur exécution.
# Claude Code envoie le contexte en JSON sur stdin ; exit 2 = blocage, stderr est renvoyé à Claude.
set -euo pipefail

cmd=$(jq -r '.tool_input.command // ""')

block() {
  echo "Commande bloquée par la factory : $1" >&2
  exit 2
}

case "$cmd" in
  *"git push"*"main"*|*"git push"*"master"*|*"git push"*"develop"*)
    block "push direct sur une branche protégée. Pousse une branche feature/* et ouvre une MR." ;;
  *"git push"*"--force"*|*"git push"*" -f"*)
    block "push forcé interdit." ;;
  *"rm -rf /"|*"rm -rf / "*|*"rm -rf ~"*|*"rm -rf ."|*"rm -rf . "*|*"rm -rf *"*)
    block "suppression récursive dangereuse." ;;
  *"db/migration"*)
    if echo "$cmd" | grep -Eq '(^|[;&| ])(rm|mv|sed -i)[[:space:]]'; then
      block "les migrations Flyway existantes ne se modifient pas : crée une nouvelle migration."
    fi ;;
  *"DROP DATABASE"*|*"drop database"*)
    block "suppression de base de données." ;;
esac

exit 0
