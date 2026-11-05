# Squelette — architecture en couches

Exécuté par `/init-architecture <feature>`. Ne crée aucune classe métier.

1. Crée un `@Bean Clock` (`Clock.systemDefaultZone()`) dans la configuration globale `<basePackage>/config/` s'il n'existe pas.
2. Crée `<basePackage>/shared/BusinessException.java` : classe abstraite qui étend `RuntimeException`,
   avec un champ `code` (String) métier.
3. Crée l'arborescence de la feature, avec un `package-info.java` par package
   (une ligne de Javadoc qui dit ce que le package contient) :
   ```
   <basePackage>/<feature>/controller
   <basePackage>/<feature>/dto
   <basePackage>/<feature>/service
   <basePackage>/<feature>/mapper
   <basePackage>/<feature>/repository
   <basePackage>/<feature>/entity
   <basePackage>/<feature>/exception
   ```
4. Crée `<feature>/controller/<Feature>ExceptionHandler.java` : `@RestControllerAdvice` limité au package de la feature,
   qui traduit `BusinessException` en `ProblemDetail` (422 par défaut) — sans encore d'exception concrète.
