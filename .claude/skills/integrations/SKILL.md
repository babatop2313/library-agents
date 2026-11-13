---
name: integrations
description: "Technologies branchées au backend (PostgreSQL, Redis, Kafka, RabbitMQ, mail, stockage objet…) : connexion, configuration, emplacement dans l'architecture, résilience, tests. À charger dès qu'une tâche touche une de ces technologies, ou pour en ajouter une."
---

# Intégrations du projet

Ce skill est un **aiguilleur**. Les intégrations actives sont listées dans `.claude/stack.json` (champ `integrations`).

## Procédure obligatoire
1. Lis `.claude/stack.json` (outil Read).
2. Identifie la ou les technologies **touchées par la tâche**. Lis **uniquement** leurs références :
   `.claude/skills/integrations/references/<techno>/REFERENCE.md`. Ne lis pas celles des autres intégrations
   (elles consomment du contexte pour rien).
3. Lis aussi `.claude/skills/architecture/references/<architecture>/roles.conf` (architecture de `factory.json`) :
   il dit **où** placer chaque rôle que joue l'intégration (section « Rôle dans l'architecture » de sa référence).
4. Si la tâche demande une technologie **absente** de `stack.json` : arrête-toi et propose `/add-integration <techno>`.
   N'ajoute jamais une dépendance d'intégration « en passant ».
5. Si la référence est marquée **« Statut : non testée »** : elle n'a pas encore été validée sur un projet réel.
   Applique-la, mais signale-le dans ta réponse et vérifie avec une attention particulière les noms d'artefacts
   et de propriétés (documentation officielle), qui peuvent avoir changé.

## Règles communes à toutes les intégrations
- Le domaine et la couche application (ou les services, en couches) n'importent **jamais** une bibliothèque d'intégration :
  elle reste dans les packages de ses rôles et dans la configuration. Le hook `guard-architecture.sh` le vérifie
  grâce au `rules.conf` de chaque intégration active.
- Connexion par **variables d'environnement** uniquement, jamais de secret dans `application.yml`.
- Toujours des **timeouts** explicites ; un service externe indisponible ne doit pas bloquer les threads de l'application.
- Tests d'intégration avec **Testcontainers** ; Docker doit tourner pour `./mvnw test`.
- Le développement local suit la section « Développement local » de la référence (service local ou conteneur).

## Contrat d'une référence d'intégration
Chaque dossier `references/<techno>/` contient `REFERENCE.md` et `rules.conf`.

`REFERENCE.md` commence par `# Intégration — <Nom>`, puis une ligne `> Statut : testée` ou `> Statut : non testée`,
puis ces sections, avec exactement ces titres :

| Section | Contenu |
|---|---|
| `## Résumé` | En deux lignes ; injecté dans chaque session pour les intégrations actives |
| `## Cas d'usage` | Quand l'utiliser, et quand **ne pas** l'utiliser |
| `## Dépendances` | Starters et modules, versions gérées par le BOM Spring Boot |
| `## Connexion et configuration` | Propriétés, variables d'environnement, timeouts, TLS |
| `## Rôle dans l'architecture` | Rôles joués, avec les clés de `roles.conf` entre backquotes |
| `## Conventions` | Nommage (clés, topics, files…), formats, versionnement |
| `## Résilience` | Comportement si le service est indisponible, retry, idempotence |
| `## Sécurité` | Authentification, chiffrement, données sensibles |
| `## Observabilité` | Health check, métriques, logs |
| `## Développement local` | Comment le lancer sur un poste (Windows, macOS, Linux) |
| `## Tests` | Module Testcontainers, tests attendus |
| `## Grille de revue` | Ce que vérifie le reviewer |

`rules.conf` : une règle par ligne, `roles :: regex :: message`. Un fichier Java qui contient une ligne correspondant à
`regex` n'est accepté que s'il se trouve dans un package d'un des `roles` (liste séparée par des virgules, clés de
`roles.conf`) ou dans un package `config`. Vérification : `tools/check-references.sh`.
