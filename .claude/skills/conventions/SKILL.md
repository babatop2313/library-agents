---
name: conventions
description: "Conventions de code Spring Boot du projet : socle non négociable, profil et options (mapping, Lombok, format des erreurs, ids) choisis dans .claude/stack.json. À charger avec le skill architecture avant TOUTE tâche backend."
---

# Conventions du projet

Ce skill est un **aiguilleur**, comme `architecture` : il te dit quelles conventions appliquer.

## Procédure obligatoire
1. Lis **en entier** `.claude/skills/conventions/references/socle/REFERENCE.md` : il s'applique toujours.
2. Lis `.claude/stack.json` (outil Read) : champs `profile` et `conventions`.
3. Lis `references/profiles/<profile>/PROFILE.md` : il donne la valeur **par défaut** de chaque option et des préférences de travail.
4. Pour chaque option (`mapping`, `lombok`, `errors`, `ids`), la valeur retenue est celle de `stack.json` si elle y figure,
   sinon celle du profil. Dans `references/options/<option>.md`, lis **uniquement** la section `## Valeur : <valeur retenue>`.
5. Si `stack.json` est absent, si le profil n'existe pas, ou si une valeur ne correspond à aucune section :
   arrête-toi et signale-le. N'invente pas de convention.

## Priorités en cas de conflit
**Architecture** (référence active) > **socle** > **options du projet** (`stack.json`) > **profil**.
Le `CLAUDE.md` du projet peut préciser, jamais contredire.

## Règles
- `stack.json` se modifie avec l'accord de l'humain (règle de permission `ask`), de préférence par `/add-integration`.
  Ne change jamais une option de toi-même pour faciliter une tâche.
- Les technologies (base, cache, messagerie…) ne sont pas ici : voir le skill `integrations`.
