# Profil `top` — préférences personnelles de Baba TOP

> Profil par défaut des projets personnels. Il fournit les **valeurs par défaut des options** et quelques préférences
> de travail. Un projet peut surcharger une option dans `.claude/stack.json` ; il ne peut jamais déroger au socle.
> Construit à partir de ce qui a été appliqué et validé sur le projet Bibliothèque : à relire et ajuster au fil des projets.

## Valeurs par défaut des options
| Option | Valeur | Pourquoi |
|---|---|---|
| `mapping` | `manuel` | Objets petits ; mappers lisibles, sans processeur d'annotations |
| `lombok` | `hors-domaine` | Confort dans les adapters, domaine pur |
| `errors` | `problem-detail` | Standard RFC 9457, géré nativement par Spring |
| `ids` | `uuid` | Identifiant connu avant l'insertion, jamais exposé si un identifiant métier existe |

## Préférences de travail
- **Langue** : code (classes, méthodes, variables) en anglais ; noms de tests, specs, messages d'erreur métier,
  commentaires et documentation en français.
- **Git** : branches `feature/<slug>`, `fix/<slug>`, `chore/<slug>` depuis la branche d'intégration ; Conventional Commits
  (`feat(<module>): …`) ; une PR par feature, jamais de push sur `main` ni `develop`.
- **Specs** : `docs/specs/<feature>.md`, règles métier numérotées `RM-xx`, critères d'acceptation en Gherkin,
  section « Décisions » complétée après validation.
- **Base de données** : PostgreSQL local pour le développement ; Docker réservé à Testcontainers.
- **Messages destinés à un assistant IA** (outils MCP) : descriptions précises en français, erreurs métier en français.
