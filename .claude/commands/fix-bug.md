---
description: Corrige un bug selon la méthode « reproduire d'abord » (test rouge → correction → test vert → revue)
argument-hint: <description du bug, stacktrace ou numéro de ticket>
---

Bug signalé : $ARGUMENTS

0. **Architecture** : lis `.claude/factory.json` ; la correction doit respecter la référence d'architecture active.
1. **Comprendre** : localise le code concerné (Grep/Glob, stacktrace). Formule l'hypothèse de cause racine en 2 lignes,
   en précisant dans quelle couche de l'architecture elle se trouve.
2. **Reproduire** : délègue à l'agent `test-writer` l'écriture d'un test qui échoue et prouve le bug. Lance-le : il doit être rouge.
3. **Corriger** : délègue à `backend-dev` ou `frontend-dev` la correction minimale de la cause racine (pas un contournement).
4. **Vérifier** : le test de reproduction passe au vert, la suite complète du module aussi.
5. **Revue** : agent `reviewer` sur le diff.
6. **Clôture** : commit `fix(<module>): <description>` et résumé : cause, correction, test ajouté.
