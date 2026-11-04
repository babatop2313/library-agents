#!/usr/bin/env bash
# PreToolUse (Edit|Write) : protège les fichiers sensibles et les migrations Flyway déjà écrites.
set -euo pipefail

input=$(cat)
file=$(echo "$input" | jq -r '.tool_input.file_path // ""')

[ -z "$file" ] && exit 0

case "$file" in
  *.env|*.env.*|*/secrets/*|*.pem|*.key|*.jks|*.p12)
    echo "Fichier sensible protégé : $file. Utilise des variables d'environnement." >&2
    exit 2 ;;
esac

# Une migration Flyway qui existe déjà ne doit jamais être modifiée.
if [[ "$file" == */db/migration/*.sql && -f "$file" ]]; then
  echo "Migration Flyway existante ($file) : ne la modifie pas, crée V<n+1>__....sql." >&2
  exit 2
fi

exit 0
