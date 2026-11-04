---
description: Prépare un backend Spring Boot à l'architecture hexagonale (annotation @UseCase, ArchUnit, Clock, squelette d'un bounded context)
argument-hint: <nom du premier bounded context, ex. loan>
---

Bounded context initial : $ARGUMENTS

Charge le skill `hexagonal-architecture`, puis :

1. Lis `backend/pom.xml` et détermine le `groupId` / package racine réel.
2. Ajoute au `pom.xml` (scope `test`) la dépendance `com.tngtech.archunit:archunit-junit5` si elle est absente.
   Si tu ne connais pas la dernière version avec certitude, laisse une propriété `${archunit.version}` et signale-le-moi.
3. Crée `shared/UseCase.java` (annotation méta-annotée `@Component` + `@Transactional`), comme dans le skill.
4. Crée un `@Bean Clock` dans la configuration globale s'il n'existe pas.
5. Crée `src/test/java/<package racine>/ArchitectureTest.java` avec les 4 règles du skill,
   et `src/test/resources/archunit.properties` contenant `archRule.failOnEmptyShould=false`
   (sinon les règles échouent tant que les packages sont vides).
6. Crée l'arborescence vide du bounded context `$ARGUMENTS` :
   `domain/{model,exception,service}`, `application/{port/in,port/out,usecase}`, `adapter/{in/web,out/persistence}`, `config`
   (un `package-info.java` par package, avec une ligne de Javadoc qui dit ce que le package contient).
7. Crée `domain/exception/DomainException.java` (abstraite, avec un `code` métier).
8. Lance `./mvnw -q test -Dtest=ArchitectureTest` : il doit passer.

Ne crée aucune classe métier. Résume les fichiers créés.
