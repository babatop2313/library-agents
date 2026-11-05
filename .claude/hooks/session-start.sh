#!/usr/bin/env bash
# SessionStart : injecte l'architecture active dans le contexte de chaque session.
# Ce que ce script affiche sur la sortie standard est ajouté au contexte de Claude.
# Il ne bloque jamais (exit 0) : en cas de problème, il affiche un avertissement.
set -uo pipefail

root="${CLAUDE_PROJECT_DIR:-$(pwd)}"
root="${root//\\//}"                       # chemins Windows → séparateurs /
cfg="$root/.claude/factory.json"
refs="$root/.claude/skills/architecture/references"

if [ ! -f "$cfg" ]; then
  echo "⚠ CLAUDE FACTORY : .claude/factory.json est absent."
  echo "Aucune architecture n'est configurée. N'écris aucun code backend : demande à l'humain de choisir"
  echo "une architecture parmi : $(ls "$refs" 2>/dev/null | grep -v '^_' | tr '\n' ' ')"
  exit 0
fi

arch=$(jq -r '.architecture // empty' "$cfg" 2>/dev/null | tr -d '\r')
ref="$refs/$arch/REFERENCE.md"

if [ -z "$arch" ] || [ ! -f "$ref" ]; then
  echo "⚠ CLAUDE FACTORY : l'architecture « $arch » indiquée dans .claude/factory.json n'existe pas."
  echo "N'écris aucun code backend et signale-le à l'humain. Architectures disponibles :"
  echo "$(ls "$refs" 2>/dev/null | grep -v '^_' | tr '\n' ' ')"
  exit 0
fi

echo "=== CLAUDE FACTORY — architecture du projet : $arch (verrouillée dans .claude/factory.json) ==="
echo "Avant toute tâche backend, charge le skill « architecture » et lis en entier :"
echo "  .claude/skills/architecture/references/$arch/REFERENCE.md"
echo "N'utilise aucune autre référence d'architecture. Ne modifie jamais .claude/factory.json."
echo
awk '/^## Résumé[[:space:]]*$/{f=1; next} /^## /{if (f) exit} f' "$ref" | tr -d '\r'

# Cohérence : un ArchitectureTest généré pour une autre architecture signale un changement d'architecture en cours de projet.
search_root="$root"
[ -d "$root/backend" ] && search_root="$root/backend"
at=$(find "$search_root" \( -name node_modules -o -name .git -o -name target \) -prune -o \
          -path '*/src/test/java/*' -name ArchitectureTest.java -print 2>/dev/null | head -1)
if [ -n "$at" ]; then
  marker=$(head -1 "$at" | tr -d '\r' | sed -n 's#^// architecture: *##p')
  if [ -n "$marker" ] && [ "$marker" != "$arch" ]; then
    echo
    echo "⛔ INCOHÉRENCE : ${at#$root/} a été généré pour l'architecture « $marker »,"
    echo "   mais .claude/factory.json indique « $arch ». L'architecture ne peut pas changer en cours de projet."
    echo "   N'écris aucun code backend et signale-le immédiatement à l'humain."
  fi
fi

exit 0
