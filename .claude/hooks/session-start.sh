#!/usr/bin/env bash
# SessionStart : injecte l'architecture active dans le contexte de chaque session.
# Ce que ce script affiche sur la sortie standard est ajouté au contexte de Claude.
# Il ne bloque jamais (exit 0) : en cas de problème, il affiche un avertissement.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

root=$(project_root)                       # chemins Windows normalisés (C:/…)
cfg="$root/.claude/factory.json"
refs="$root/.claude/skills/architecture/references"
version=$(head -1 "$root/.claude/factory-version" 2>/dev/null | tr -d '\r[:space:]')

# Sans jq, aucun hook ne peut lire ce que Claude Code lui envoie : les protections bloquent tout par sécurité.
if ! command -v jq >/dev/null 2>&1; then
  echo "⚠ CLAUDE FACTORY : jq est introuvable. Les hooks de protection bloqueront toutes les modifications tant qu'il manque."
  echo "Signale-le à l'humain avant toute autre action. $(jq_install_hint)"
  exit 0
fi

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

base=$(jq -r '.baseBranch // "develop"' "$cfg" 2>/dev/null | tr -d '\r')
echo "=== CLAUDE FACTORY ${version:+v$version }— architecture du projet : $arch (verrouillée dans .claude/factory.json) ==="
echo "Branche d'intégration : $base (les branches feature/* partent de $base à jour)."
echo "Avant toute tâche backend, charge le skill « architecture » et lis en entier :"
echo "  .claude/skills/architecture/references/$arch/REFERENCE.md"
echo "N'utilise aucune autre référence d'architecture. Ne modifie jamais .claude/factory.json (le lire avec Read est autorisé)."
echo
awk '/^## Résumé[[:space:]]*$/{f=1; next} /^## /{if (f) exit} f' "$ref" | tr -d '\r'

# Ancien format (≤ v3.2) : la version du kit était dans factory.json.
if jq -e 'has("factoryVersion")' "$cfg" >/dev/null 2>&1; then
  echo
  echo "ℹ .claude/factory.json contient encore « factoryVersion » (format v3.2 ou antérieur). Depuis la v3.3, la version"
  echo "  du kit est dans .claude/factory-version. Signale à l'humain qu'il peut retirer ce champ (et ajouter « baseBranch »)."
  echo "  Ne modifie pas factory.json toi-même : il est verrouillé."
fi

# Cohérence : un ArchitectureTest généré pour une autre architecture signale un changement d'architecture en cours de projet.
search_root="$root"
[ -d "$root/backend" ] && search_root="$root/backend"
at=$(find "$search_root" \( -name node_modules -o -name .git -o -name target \) -prune -o \
          -path '*/src/test/java/*' -name ArchitectureTest.java -print 2>/dev/null | head -1)
if [ -n "$at" ]; then
  at=$(norm_path "$at")
  marker=$(head -1 "$at" | tr -d '\r' | sed -n 's#^// architecture: *##p')
  if grep -q '__BASE_PACKAGE__' "$at" 2>/dev/null; then
    echo
    echo "⛔ ArchitectureTest INCOMPLET : ${at#"$root"/} contient encore le marqueur __BASE_PACKAGE__."
    echo "   Les règles concernées analysent un package qui n'existe pas et passent au vert SANS RIEN VÉRIFIER."
    echo "   Signale-le à l'humain et propose de lancer /update-architecture-test avant toute tâche backend."
  fi
  if [ -n "$marker" ] && [ "$marker" != "$arch" ]; then
    echo
    echo "⛔ INCOHÉRENCE : ${at#"$root"/} a été généré pour l'architecture « $marker »,"
    echo "   mais .claude/factory.json indique « $arch ». L'architecture ne peut pas changer en cours de projet."
    echo "   N'écris aucun code backend et signale-le immédiatement à l'humain."
  fi
fi

exit 0
