---
name: architecture
description: "Architecture backend imposée au projet (en couches, hexagonale…) — indique quelle référence d'architecture est active et oblige à la lire. À charger avant TOUTE tâche backend : conception, écriture de code, tests, revue, correction de bug."
---

# Architecture du projet

Ce skill est un **aiguilleur**. Il ne contient pas les règles d'architecture : il te dit laquelle appliquer.

## Procédure obligatoire
1. Lis `.claude/factory.json` et récupère la valeur du champ `architecture` (ex. `hexagonal`).
2. Lis **en entier** le fichier `.claude/skills/architecture/references/<architecture>/REFERENCE.md`.
3. Applique uniquement cette référence. **N'ouvre et n'utilise AUCUNE autre référence** du dossier `references/`,
   même si elle te paraît plus adaptée à la tâche.
4. Si `factory.json` est absent, ou si la référence indiquée n'existe pas : **arrête-toi** et signale-le.
   N'invente pas d'architecture et ne choisis pas à la place de l'humain.

## Règles
- L'architecture est choisie **une fois, au démarrage du projet**, par un humain, dans `factory.json`.
- Tu ne modifies jamais `factory.json` (un hook et une règle de permission l'interdisent de toute façon).
- Si une demande t'amène à violer la règle de dépendance de la référence active, refuse et propose
  une solution conforme. Ne « simplifie » jamais en changeant de structure.
- Si du code existant contredit la référence (ex. `ArchitectureTest` généré pour une autre architecture),
  arrête-toi et signale l'incohérence au lieu de choisir toi-même.

## Contrat d'une référence
Chaque dossier `references/<nom>/` contient exactement :

| Fichier | Rôle |
|---|---|
| `REFERENCE.md` | Les règles, avec les 9 sections normalisées ci-dessous |
| `ArchitectureTest.java` | Modèle des règles ArchUnit, copié dans le projet par `/init-architecture` |
| `rules.conf` | Imports interdits par couche, lus par le hook `guard-architecture.sh` |
| `scaffold.md` | Étapes de création du squelette, exécutées par `/init-architecture` |
| `roles.conf` | Package de chaque rôle technique (vocabulaire : `skills/integrations/roles.conf`), lu par le skill `integrations` et le hook |

Sections obligatoires de `REFERENCE.md`, avec exactement ces titres :

| Section | Utilisée par |
|---|---|
| `## Résumé` | injectée dans chaque session par le hook `session-start.sh` |
| `## Structure des packages` | architect, backend-dev |
| `## Règle de dépendance` | tous |
| `## Ordre d'implémentation` | backend-dev, architect |
| `## Règles du développeur` | backend-dev |
| `## Exemples de code` | backend-dev |
| `## Tests par couche` | test-writer |
| `## Grille de revue` | reviewer |
| `## Format de réponse finale` | backend-dev, test-writer |

La section `## Structure des packages` contient une sous-section `### Rôles techniques` qui cite chaque rôle de `roles.conf`.

Pour ajouter une architecture : copier `references/_template/`, remplir les 5 fichiers,
puis lancer `tools/check-references.sh` pour vérifier le contrat.
