# Référence d'architecture — <NOM>

<!--
  Modèle pour ajouter une architecture (ex. clean, modular-monolith).
  1. Copie le dossier _template/ sous un nouveau nom court, en minuscules : references/<nom>/
  2. Remplis les 9 sections ci-dessous. Garde EXACTEMENT ces titres : les agents et les hooks les cherchent par leur nom.
  3. Remplis ArchitectureTest.java, rules.conf et scaffold.md.
  4. Vérifie le contrat : bash tools/check-references.sh
  Supprime ces commentaires une fois la référence écrite.
-->

## Résumé
<!-- 10 à 15 lignes maximum. Injecté au début de chaque session : c'est ce que Claude a toujours sous les yeux.
     Commence par « **Architecture active : <nom>.** », puis : packages, règle de dépendance en une ligne,
     les 3 à 5 interdits majeurs, l'ordre d'implémentation en une ligne. -->

## Structure des packages
<!-- Arborescence complète avec, pour chaque package, ce qu'il contient et un exemple de nom de classe. -->

## Règle de dépendance
<!-- Schéma + tableau « Couche | Peut dépendre de | Ne doit jamais dépendre de ». -->

## Ordre d'implémentation
<!-- Liste numérotée des étapes, avec le test à écrire à chaque étape. Dernière étape : ./mvnw test + ArchitectureTest. -->

## Règles du développeur
<!-- Règles concrètes et vérifiables (« jamais X dans Y »), une par puce. -->

## Exemples de code
<!-- Un exemple court et complet par couche, sur le domaine Bibliothèque si possible. -->

## Tests par couche
<!-- Tableau « Couche | Type de test | Outils | Exemple », puis où se teste une règle métier. -->

## Grille de revue
<!-- Erreurs à refuser en revue, une par puce. Préciser que toute violation de la règle de dépendance est BLOQUANT. -->

## Format de réponse finale
<!-- Bloc de texte que backend-dev et test-writer recopient à la fin de leur travail.
     Doit commencer par « Architecture appliquée : <nom> ». -->
