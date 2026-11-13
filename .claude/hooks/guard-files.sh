#!/usr/bin/env bash
# PreToolUse (Edit|Write) : protège le verrou d'architecture, les fichiers sensibles
# et les migrations Flyway déjà écrites. exit 2 = blocage, stderr est renvoyé à Claude.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
need_jq block

file=$(norm_path "$(jq -r '.tool_input.file_path // ""')")
[ -z "$file" ] && exit 0

# Verrou : l'architecture est choisie une fois, par un humain, au démarrage du projet.
if [[ "$file" == */.claude/factory.json || "$file" == .claude/factory.json ]]; then
  echo "Fichier verrouillé : .claude/factory.json fixe l'architecture du projet et ne peut pas être modifié par Claude." >&2
  echo "Changer d'architecture en cours de projet est interdit. Si c'est vraiment nécessaire, c'est une décision humaine." >&2
  exit 2
fi

case "$file" in
  *.env|*.env.*|*/secrets/*|*.pem|*.key|*.jks|*.p12)
    [[ "$file" == *.env.example ]] && exit 0
    echo "Fichier sensible protégé : $file. Utilise des variables d'environnement." >&2
    exit 2 ;;
esac

# Une migration Flyway qui existe déjà ne doit jamais être modifiée.
if [[ "$file" == */db/migration/*.sql && -f "$file" ]]; then
  echo "Migration Flyway existante ($file) : ne la modifie pas, crée V<n+1>__....sql." >&2
  exit 2
fi

exit 0
