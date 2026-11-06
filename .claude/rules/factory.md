# Règles de la Claude Factory (communes à tous les projets)

> Fichier fourni par le kit `factory-dev` et chargé automatiquement à chaque session (dossier `.claude/rules/`).
> **Ne pas modifier dans un projet** : il est remplacé à chaque mise à jour du kit.
> Ce qui est propre au projet va dans le `CLAUDE.md` à la racine.

## Architecture backend
- **L'architecture est choisie une seule fois, au démarrage du projet, dans `.claude/factory.json`** (champ `architecture`).
  Elle ne change jamais en cours de développement.
- Les règles complètes sont dans `.claude/skills/architecture/references/<architecture>/REFERENCE.md`.
  Leur résumé est injecté au début de chaque session par le hook `session-start.sh`.
- Avant **toute** tâche backend (conception, code, tests, revue, bug) : charger le skill `architecture`
  et lire la référence active. N'utiliser aucune autre référence.
- Ne jamais modifier `.claude/factory.json` (verrouillé par un hook et une règle de permission).
  Le **lire** est autorisé, de préférence avec l'outil Read.

## Façon de travailler
- **Les fichiers source se créent et se modifient avec les outils Write et Edit, jamais par le shell**
  (`echo >`, `cat <<EOF`, `sed -i`, boucle `mkdir` + écriture…). Les hooks de vérification (architecture, compilation,
  fichiers protégés) ne surveillent que Write et Edit : un fichier écrit par le shell leur échappe.
- Le shell sert à compiler, tester, lire l'historique Git et créer des branches ou des commits.
- Si un hook bloque une action, ne pas chercher à le contourner : corriger, ou signaler le blocage à l'humain.
- **Après la suppression, le déplacement ou le renommage d'un fichier Java**, compiler et tester avec `./mvnw clean test`
  (ou `clean compile`) : sinon les `.class` obsolètes de `target/` faussent `ArchitectureTest`, qui analyse les classes compilées.

## Règles non négociables
- Respecter la règle de dépendance de la référence d'architecture active. Une violation est toujours bloquante.
- Jamais d'entité JPA exposée par un contrôleur : toujours des DTO.
- Toute modification de schéma passe par une nouvelle migration Flyway ; ne jamais modifier une migration existante.
- Pas de secret dans le code ni dans `application.yml` : variables d'environnement. Jamais de token ni de donnée personnelle dans les logs.
- Toute règle métier est testée, dans la couche où la référence place la logique métier.
- `ArchitectureTest` doit toujours passer ; on corrige le code, jamais les règles.
- Ne jamais pousser sur `main`/`develop` ni merger : on produit une branche et une Pull Request / Merge Request.

## Workflow attendu
1. Spec dans `docs/specs/<feature>.md` (agent `spec-writer`), validée par l'humain.
2. Plan technique (agent `architect`) validé par l'humain, enregistré dans `docs/specs/<feature>-plan.md`.
3. Implémentation (agent `backend-dev`) dans l'ordre d'implémentation de la référence d'architecture.
4. Tests (agent `test-writer`) selon la section « Tests par couche » de la référence.
5. Revue (agent `reviewer`) → corrections.
6. Commit au format Conventional Commits : `feat(<module>): <description>`.

## Commandes de la factory
| Commande | Rôle |
|---|---|
| `/init-architecture <module>` | Une fois au démarrage : ArchUnit, squelette du premier module |
| `/update-architecture-test` | Après une mise à jour du kit : recopie `ArchitectureTest` depuis la référence active |
| `/feature <besoin>` | Spec → plan → code → tests → revue |
| `/fix-bug <bug>` | Test qui reproduit → correction → revue |
| `/review-mr [branche cible]` | Revue des changements de la branche courante |
