#!/usr/bin/env bash
# PostToolUse (Bash|PowerShell) : après une suppression, un déplacement ou un renommage de fichier .java par le shell,
# rappelle à Claude que les .class de target/ sont peut-être obsolètes (ArchUnit analyse les classes compilées).
# Retour NON bloquant (additionalContext). Ne bloque jamais.
set -uo pipefail
source "$(dirname "$0")/lib.sh"

cmd=$(jq -r '.tool_input.command // ""' | tr -d '\r')
ncmd="${cmd//\\//}"

if printf '%s' "$ncmd" | grep -Eq '(^|[;&|[:space:]])(rm|mv|git[[:space:]]+(rm|mv)|Remove-Item|Move-Item|Rename-Item)[[:space:]][^;&|]*(\.java|src/main/java|src/test/java)'; then
  post_tool_context "CLAUDE FACTORY : un fichier Java a été supprimé, déplacé ou renommé. Les classes compilées de backend/target/ peuvent être obsolètes : ArchitectureTest analyse les .class et peut signaler une violation qui n'existe plus, ou en masquer une. Pour la prochaine compilation ou exécution des tests, utilise ./mvnw clean test (ou clean compile)."
fi
exit 0
