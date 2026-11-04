---
description: Revue complète des changements de la branche courante par rapport à une branche cible
argument-hint: [branche cible, défaut develop]
---

Branche cible : $ARGUMENTS (si vide, utilise `develop`)

1. Récupère le diff : `git diff <cible>...HEAD --stat` puis le diff complet.
2. Délègue la revue à l'agent `reviewer` en lui transmettant la liste des fichiers modifiés et la branche cible.
3. Si une spec correspondante existe dans `docs/specs/`, demande au reviewer de vérifier la conformité.
4. Rends le rapport du reviewer tel quel, suivi d'une proposition de message de commentaire de MR (court, en français).

Ne modifie aucun fichier.
