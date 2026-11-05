# Squelette — architecture hexagonale

Exécuté par `/init-architecture <boundedContext>`. Ne crée aucune classe métier.

1. Crée `<basePackage>/shared/UseCase.java` : annotation méta-annotée `@Component` + `@Transactional`
   (voir « Exemples de code » de la référence).
2. Crée un `@Bean Clock` (`Clock.systemDefaultZone()`) dans la configuration globale `<basePackage>/config/` s'il n'existe pas.
3. Crée l'arborescence du bounded context, avec un `package-info.java` par package
   (une ligne de Javadoc qui dit ce que le package contient) :
   ```
   <basePackage>/<boundedContext>/domain/model
   <basePackage>/<boundedContext>/domain/exception
   <basePackage>/<boundedContext>/domain/service
   <basePackage>/<boundedContext>/application/port/in
   <basePackage>/<boundedContext>/application/port/out
   <basePackage>/<boundedContext>/application/usecase
   <basePackage>/<boundedContext>/adapter/in/web
   <basePackage>/<boundedContext>/adapter/out/persistence
   <basePackage>/<boundedContext>/config
   ```
4. Crée `<boundedContext>/domain/exception/DomainException.java` : classe abstraite qui étend `RuntimeException`,
   avec un champ `code` (String) métier. Aucun import en dehors du JDK.
