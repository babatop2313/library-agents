# Intégration — <Nom>
> Statut : non testée

<!-- Copier ce dossier sous references/<techno>/ (nom en minuscules, avec des tirets), remplir chaque section,
     puis lancer tools/check-references.sh. Passer le statut à « testée » seulement après validation sur un projet réel. -->

## Résumé
<!-- Deux lignes : ce que fait l'intégration, et le rôle qu'elle joue. Injecté dans chaque session. -->

## Cas d'usage
<!-- Quand l'utiliser ; quand NE PAS l'utiliser (et quoi utiliser à la place). -->

## Dépendances
<!-- Starters et modules (versions gérées par le BOM Spring Boot), dépendances de test. -->

## Connexion et configuration
<!-- Bloc application.yml avec des ${VARIABLES} ; timeouts ; TLS ; aucune valeur par défaut pour un secret. -->

## Rôle dans l'architecture
<!-- Rôles joués, avec les clés de skills/integrations/roles.conf entre backquotes (ex. `sortant-cache`),
     et ce que contient chaque classe. -->

## Conventions
<!-- Nommage, formats, versionnement. -->

## Résilience
<!-- Service indisponible, retry, idempotence, cohérence avec la transaction de base de données. -->

## Sécurité
<!-- Authentification, chiffrement, données sensibles. -->

## Observabilité
<!-- Health check Actuator, métriques, logs. -->

## Développement local
<!-- Lancer le service sur un poste : Windows, macOS, Linux (service local ou conteneur). -->

## Tests
<!-- Module Testcontainers, configuration de test, tests attendus. -->

## Grille de revue
<!-- Liste de points vérifiables par le reviewer. -->
