# Option `ids` — identifiants techniques des entités

Clé dans `.claude/stack.json` : `"conventions": { "ids": "<valeur>" }`.

## Valeur : uuid
- Colonne `id UUID` sans valeur par défaut : l'identifiant est généré **par l'application** (objet de valeur `XxxId`
  dans le domaine, `UUID.randomUUID()` ou `UUID v7` si la version de Java le propose).
- Avantages : identifiant connu avant l'insertion, pas de séquence, fusion de données facilitée.
- L'identifiant technique n'est jamais exposé quand un identifiant métier existe (ISBN, numéro d'adhérent…).

## Valeur : sequence
- Colonne `id BIGINT` alimentée par une séquence PostgreSQL nommée `seq_<table>` (`@SequenceGenerator`,
  `allocationSize` égal à l'`INCREMENT BY` de la séquence).
- Jamais `GenerationType.IDENTITY` avec un traitement par lots (il désactive le batch insert de Hibernate).
- Ne jamais exposer un identifiant séquentiel dans une URL publique (il est devinable) : utiliser un identifiant métier.
