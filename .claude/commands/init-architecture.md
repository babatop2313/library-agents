---
description: Initialise le backend dans l'architecture choisie dans .claude/factory.json (ArchUnit, squelette du premier module). À lancer une seule fois, au démarrage du projet.
argument-hint: <nom du premier module / bounded context, ex. catalog>
---

Premier module : $ARGUMENTS

1. **Architecture** : lis `.claude/factory.json` (champ `architecture`), puis lis en entier
   `.claude/skills/architecture/references/<architecture>/REFERENCE.md`.
   Si `factory.json` est absent ou si la référence n'existe pas : **arrête-toi**, liste les architectures disponibles
   (dossiers de `.claude/skills/architecture/references/` sauf `_template`) et demande-moi de renseigner `factory.json` moi-même.
2. **Garde-fou** : cherche un `ArchitectureTest.java` existant dans `backend/src/test/java/`.
   - S'il existe et que sa première ligne indique une **autre** architecture (`// architecture: <autre>`) : **arrête-toi**,
     le projet a déjà été initialisé avec une autre architecture. Ne change rien.
   - S'il existe avec la même architecture : le projet est déjà initialisé. Passe directement à l'étape 6 pour le nouveau module.
3. Lis `backend/pom.xml` et détermine le package racine réel (`<basePackage>`).
4. Ajoute au `pom.xml` (scope `test`) la dépendance `com.tngtech.archunit:archunit-junit5` si elle est absente.
   Si tu ne connais pas la dernière version avec certitude, mets une propriété `${archunit.version}` et signale-le-moi.
5. Copie `.claude/skills/architecture/references/<architecture>/ArchitectureTest.java` vers
   `backend/src/test/java/<basePackage en chemin>/ArchitectureTest.java`, en remplaçant `__BASE_PACKAGE__` par `<basePackage>`.
   Garde la première ligne `// architecture: <architecture>` telle quelle.
   Crée `backend/src/test/resources/archunit.properties` contenant `archRule.failOnEmptyShould=false`
   (sinon les règles échouent tant que les packages sont vides).
6. Exécute les étapes de `.claude/skills/architecture/references/<architecture>/scaffold.md` pour le module `$ARGUMENTS`.
7. Lance `./mvnw -q test -Dtest=ArchitectureTest` (depuis `backend/`) : il doit passer.

Ne crée aucune classe métier. Termine par : l'architecture appliquée, les fichiers créés, le résultat d'`ArchitectureTest`.
