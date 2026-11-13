#!/usr/bin/env bash
# PreToolUse (Bash|PowerShell) : bloque les commandes dangereuses avant leur exécution.
# Claude Code envoie le contexte en JSON sur stdin ; exit 2 = blocage, stderr est renvoyé à Claude.
# Chaque règle est indépendante : une commande est vérifiée par toutes les règles.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
need_jq block

cmd=$(jq -r '.tool_input.command // ""' | tr -d '\r')
ncmd="${cmd//\\//}"                        # séparateurs Windows → / pour les comparaisons de chemins

block() {
  echo "Commande bloquée par la factory : $1" >&2
  exit 2
}
has() { printf '%s' "$ncmd" | grep -Eq "$1"; }

# Fin d'un argument : espace, fin de commande, guillemet ou séparateur de commandes.
END='(["'"'"']?([[:space:]]|$|[;&|]))'

# --- Git : branches protégées, push forcé ---
if has 'git[[:space:]]+push'; then
  has 'git[[:space:]]+push.*([[:space:]:+]|refs/heads/)(main|master|develop)'"$END" \
    && block "push direct sur une branche protégée (main, master, develop). Pousse une branche feature/* et ouvre une PR/MR."
  has 'git[[:space:]]+push.*([[:space:]]--force|[[:space:]]-f'"$END"'|[[:space:]]\+[A-Za-z0-9_./-]+)' \
    && block "push forcé interdit."
fi

# --- Fusion de PR/MR : toujours faite par un humain (vaut aussi pour l'outil PowerShell) ---
has '(^|[;&|[:space:]])(gh[[:space:]]+pr[[:space:]]+merge|glab[[:space:]]+mr[[:space:]]+merge)'"$END" \
  && block "la fusion d'une Pull Request / Merge Request est réservée à un humain."

# --- Suppressions dangereuses ---
has '(^|[;&|[:space:]])rm[[:space:]]+-[a-zA-Z]*[rR][a-zA-Z]*[[:space:]]+("|'"'"')?(/|~|\.|\*|\.\.)'"$END" \
  && block "suppression récursive dangereuse."
has 'DROP[[:space:]]+DATABASE|drop[[:space:]]+database' \
  && block "suppression de base de données."

# --- Migrations Flyway : jamais de modification, déplacement ou suppression d'un .sql existant ---
MIG='[^[:space:];&|]*db/migration/[^[:space:];&|]*\.sql'
if has "$MIG"; then
  has '(^|[;&|[:space:]])(rm|mv|truncate|sed[[:space:]]+-i|perl[[:space:]]+-i|git[[:space:]]+rm|Remove-Item|Move-Item|Set-Content|Clear-Content)[[:space:]][^;&|]*'"$MIG" \
    && block "les migrations Flyway existantes ne se modifient pas : crée une nouvelle migration V<n+1>__....sql avec l'outil Write."
  has '>>?[[:space:]]*("|'"'"')?'"$MIG" \
    && block "pas d'écriture de migration par le shell : utilise l'outil Write pour créer une nouvelle migration."
fi

# --- Verrou d'architecture : aucune écriture vers .claude/factory.json (la lecture reste autorisée) ---
FJ='[^[:space:];&|]*factory\.json'
if has 'factory\.json'; then
  has '>>?[[:space:]]*("|'"'"')?'"$FJ" \
    && block ".claude/factory.json est verrouillé : l'architecture du projet ne peut pas être modifiée par Claude."
  has '(^|[;&|[:space:]])(rm|mv|truncate|tee|sed[[:space:]]+-i|perl[[:space:]]+-i|git[[:space:]]+(checkout|restore|rm)|Set-Content|Out-File|Add-Content|Clear-Content|Remove-Item|Move-Item)[[:space:]][^;&|]*'"$FJ" \
    && block ".claude/factory.json est verrouillé : l'architecture du projet ne peut pas être modifiée par Claude."
  # copie VERS factory.json (factory.json est le dernier argument)
  has '(^|[;&|[:space:]])(cp|Copy-Item)[[:space:]][^;&|]*[[:space:]]("|'"'"')?'"$FJ"'("|'"'"')?[[:space:]]*($|[;&|])' \
    && block ".claude/factory.json est verrouillé : l'architecture du projet ne peut pas être modifiée par Claude."
fi

exit 0
