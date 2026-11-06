---
description: Orchestre la factory de bout en bout pour une nouvelle fonctionnalité (spec → plan → code → tests → revue)
argument-hint: <description de la fonctionnalité>
---

Fonctionnalité demandée : $ARGUMENTS

Tu es l'orchestrateur de la software factory. Délègue chaque étape au sous-agent indiqué et ne code pas toi-même.
**Ne passe jamais à l'étape suivante tant que l'agent de l'étape en cours n'a pas rendu son résultat**,
y compris quand il tourne en arrière-plan.

0. **Architecture** : lis `.claude/factory.json` avec l'outil Read et annonce-moi l'architecture du projet.
   Si le fichier est absent ou si la référence correspondante n'existe pas, arrête-toi.
   Transmets ce nom d'architecture à chaque agent.
1. **Branche** : crée une branche `feature/<slug>` depuis la branche courante (`git checkout -b`).
2. **Spec** → agent `spec-writer`, qui écrit `docs/specs/<slug>.md`.
   Présente-moi le chemin de la spec et les questions ouvertes, puis **ARRÊTE-TOI et ATTENDS** mes réponses et ma validation.
   Reporte mes réponses dans la spec (section « Décisions ») avant de continuer.
3. **Plan** → agent `architect` avec le chemin de la spec. Présente-moi le plan et **ARRÊTE-TOI et ATTENDS** ma validation explicite.
   Une fois validé, enregistre le plan **et mes décisions** dans `docs/specs/<slug>-plan.md` (jamais dans un dossier temporaire) :
   c'est ce fichier que tu transmets aux agents suivants.
4. **Implémentation** :
   - agent `backend-dev` avec `docs/specs/<slug>-plan.md` (partie backend), dans l'ordre d'implémentation de la référence ;
     il implémente et compile, **il n'écrit pas les tests** ;
   - puis agent `frontend-dev` avec le plan et le contrat d'API (partie frontend), si la feature en a une.
5. **Tests** → agent `test-writer` avec la spec, le plan et la liste des fichiers produits.
   Il écrit **tous** les tests, couche par couche, selon la section « Tests par couche » de la référence, et les exécute.
   S'il détecte un bug dans le code de production, renvoie-le à `backend-dev`, puis relance `test-writer`.
6. **Revue** → agent `reviewer`. S'il y a des remarques BLOQUANT ou MAJEUR, renvoie-les à l'agent dev concerné, puis relance la revue.
   Maximum 2 boucles ; au-delà, arrête-toi et fais-moi un point.
7. **Clôture** : commit au format Conventional Commits (code, tests, spec et plan), puis donne-moi un résumé :
   architecture appliquée, fichiers modifiés par couche, tests (passés/échoués), verdict de la revue,
   et la commande pour pousser la branche.

Ne pousse rien et n'ouvre pas de PR/MR sans mon accord.
