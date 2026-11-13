#!/usr/bin/env bash
# PreToolUse (mcp__postgres__.*) : n'autorise que des requêtes SQL de LECTURE envoyées au serveur MCP PostgreSQL.
# Troisième ligne de défense, après le rôle PostgreSQL en lecture seule et le mode « restricted » du serveur.
# exit 2 = blocage, stderr est renvoyé à Claude.
#
# Politique (volontairement stricte : en cas de doute, on bloque) :
#  - une seule instruction ;
#  - qui commence par SELECT, WITH, EXPLAIN, SHOW, VALUES ou TABLE ;
#  - sans aucun mot-clé d'écriture, même dans une sous-requête ou une CTE (WITH x AS (DELETE …)) ;
#  - sans « dollar quoting » ($$ … $$) ni commentaire, qui peuvent cacher du code.
set -uo pipefail
source "$(dirname "$0")/lib.sh"
need_jq block

input=$(cat)
sql=$(printf '%s' "$input" | jq -r '.tool_input.sql // .tool_input.query // .tool_input.statement // ""')
[ -z "$sql" ] && exit 0          # outil sans SQL (list_schemas, get_object_details…)

block() {
  echo "Requête SQL bloquée par la factory : $1" >&2
  echo "Le serveur MCP PostgreSQL est en lecture seule : SELECT, WITH … SELECT, EXPLAIN (sans ANALYZE d'une écriture), SHOW." >&2
  exit 2
}

# 1. Mise à plat : une seule ligne, sans retours chariot ni tabulations.
s=$(printf '%s' "$sql" | tr '\r\n\t' '   ')
# 2. Dollar quoting interdit (corps de fonction, DO $$ … $$).
printf '%s' "$s" | grep -Eq '\$[A-Za-z_]*\$' && block "le « dollar quoting » (\$\$ … \$\$) n'est pas autorisé."
# 3. Contenu des chaînes ('…', "…") neutralisé : il ne doit ni cacher ni déclencher une règle.
#    Une chaîne mal fermée laisse son contenu visible, donc vérifié : en cas de doute, on bloque.
s=$(printf '%s' "$s" | sed -e "s/'[^']*'/''/g" -e 's/"[^"]*"/""/g')
#    Commentaires interdits : supprimer un commentaire peut révéler ou masquer une instruction
#    (« SELECT 1 --<retour à la ligne>; DELETE … »). Claude n'en a pas besoin pour lire la base.
case "$s" in *"--"*|*"/*"*) block "les commentaires SQL (-- et /* */) ne sont pas autorisés." ;; esac
# 4. Majuscules, espaces superflus, point-virgule final toléré.
s=$(printf '%s' "$s" | tr '[:lower:]' '[:upper:]' | sed -E -e 's/ +/ /g' -e 's/^ //' -e 's/[ ;]*$//')
[ -z "$s" ] && block "requête vide."

# 5. Une seule instruction.
case "$s" in *";"*) block "plusieurs instructions dans un même appel." ;; esac

# 6. Premier mot autorisé.
first=${s%% *}
case "$first" in
  SELECT|WITH|EXPLAIN|SHOW|VALUES|TABLE|"("*) ;;
  *) block "seules les lectures sont autorisées (instruction « $first »)." ;;
esac

# 7. Aucun mot-clé d'écriture, où qu'il soit : CTE qui modifie (WITH x AS (DELETE …)), SELECT … INTO,
#    EXPLAIN ANALYZE (qui exécute vraiment la requête), EXPLAIN d'une écriture, FOR UPDATE…
#    Les autres instructions (VACUUM, SET, LOCK…) sont déjà refusées par la règle du premier mot.
W='INSERT|UPDATE|DELETE|MERGE|INTO|CREATE|DROP|ALTER|TRUNCATE|GRANT|REVOKE|COPY|CALL|EXECUTE|DECLARE|ANALYZE'
if printf '%s' "$s" | grep -Eq "(^|[^A-Z0-9_\$])($W)([^A-Z0-9_]|\$)"; then
  kw=$(printf '%s' "$s" | grep -Eo "(^|[^A-Z0-9_\$])($W)([^A-Z0-9_]|\$)" | head -1 | tr -cd 'A-Z')
  block "mot-clé d'écriture ou d'administration interdit : $kw."
fi

# 8. Fonctions à effet de bord.
F='PG_TERMINATE_BACKEND|PG_CANCEL_BACKEND|PG_RELOAD_CONF|PG_ROTATE_LOGFILE|SET_CONFIG|PG_READ_FILE|PG_READ_BINARY_FILE|PG_LS_DIR|LO_IMPORT|LO_EXPORT|DBLINK[A-Z_]*|PG_SLEEP[A-Z_]*|NEXTVAL|SETVAL|PG_ADVISORY[A-Z_]*'
if printf '%s' "$s" | grep -Eq "(^|[^A-Z0-9_])($F) *\("; then
  fn=$(printf '%s' "$s" | grep -Eo "($F) *\(" | head -1 | tr -cd 'A-Z_')
  block "fonction à effet de bord interdite : $fn()."
fi

exit 0
