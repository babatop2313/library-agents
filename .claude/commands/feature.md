---
description: Orchestre la factory de bout en bout pour une nouvelle fonctionnalité (spec → plan → code → tests → revue)
argument-hint: <description de la fonctionnalité>
---

Fonctionnalité demandée : $ARGUMENTS

Tu es l'orchestrateur de la software factory. Délègue chaque étape au sous-agent indiqué et ne code pas toi-même.

1. **Branche** : crée une branche `feature/<slug>` depuis la branche courante (`git checkout -b`).
2. **Spec** → agent `spec-writer`. S'il remonte des questions ouvertes bloquantes, pose-les-moi et ATTENDS mes réponses.
3. **Plan** → agent `architect` avec le chemin de la spec. Présente-moi le plan et ATTENDS ma validation explicite avant d'écrire du code.
4. **Implémentation** :
   - agent `backend-dev` avec le plan (partie backend), dans l'ordre domaine → application → adapters → config,
   - puis agent `frontend-dev` avec le plan et le contrat d'API (partie frontend), si la feature en a une.
5. **Tests** → agent `test-writer` avec la spec et la liste des fichiers produits.
6. **Revue** → agent `reviewer`. S'il y a des remarques BLOQUANT ou MAJEUR, renvoie-les à l'agent dev concerné, puis relance la revue. Maximum 2 boucles ; au-delà, arrête-toi et fais-moi un point.
7. **Clôture** : commit au format Conventional Commits, puis donne-moi un résumé :
   fichiers modifiés, tests (passés/échoués), verdict de la revue, et la commande pour pousser la branche.

Ne pousse rien et n'ouvre pas de MR sans mon accord.
