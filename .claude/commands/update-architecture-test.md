---
description: Met à jour ArchitectureTest.java du projet depuis la référence d'architecture active du kit (après une mise à jour de la factory). Ne change jamais d'architecture.
argument-hint: (aucun argument)
---

Objectif : remplacer `ArchitectureTest.java` du projet par la version de la référence d'architecture active,
pour récupérer les nouvelles règles d'une mise à jour du kit. **Ce n'est jamais un changement d'architecture.**

1. **Architecture** : lis `.claude/factory.json` avec l'outil Read (champ `architecture`).
   Si le fichier est absent ou si `.claude/skills/architecture/references/<architecture>/ArchitectureTest.java` n'existe pas : arrête-toi.
2. **Fichier existant** : trouve `ArchitectureTest.java` dans `backend/src/test/java/`.
   - Absent : arrête-toi et propose `/init-architecture` à la place.
   - Sa première ligne indique une **autre** architecture (`// architecture: <autre>`) : **arrête-toi**. Le projet a été initialisé
     avec une autre architecture que celle de `factory.json` : c'est une incohérence à signaler à l'humain, pas à corriger.
3. **Package racine** : lis la ligne `package …;` du fichier existant. Si elle contient encore `__BASE_PACKAGE__`,
   détermine le package racine à partir de la classe annotée `@SpringBootApplication`.
4. **Écart** : compare les règles (`@ArchTest static final ArchRule <nom>`) du fichier existant et du modèle de la référence.
   Présente-moi : règles ajoutées, règles retirées, règles modifiées. Si le fichier existant a été modifié à la main,
   signale-le clairement.
5. **Mise à jour** : avec l'outil **Write** (jamais par le shell), réécris le fichier à partir du modèle de la référence,
   en remplaçant **toutes** les occurrences de `__BASE_PACKAGE__` par le package racine.
   Garde la première ligne `// architecture: <architecture>` telle quelle.
   Vérifie que `backend/src/test/resources/archunit.properties` contient `archRule.failOnEmptyShould=false`.
6. **Contrôle** : vérifie qu'il ne reste aucun `__BASE_PACKAGE__` dans le fichier, puis lance
   `./mvnw -q clean test -Dtest=ArchitectureTest`, depuis `backend/` (le `clean` évite des classes compilées obsolètes).
7. **Si une nouvelle règle échoue** : ne modifie ni la règle ni le code. Liste les violations, avec pour chacune la règle
   et la classe en cause, et propose un plan de correction. C'est une dette d'architecture révélée par la mise à jour :
   la décision appartient à l'humain.

Termine par : architecture, règles ajoutées ou modifiées, résultat d'`ArchitectureTest`.
