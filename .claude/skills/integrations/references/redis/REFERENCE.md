# Intégration — Redis
> Statut : non testée

## Résumé
Cache clé-valeur en mémoire, partagé entre les instances de l'application, accédé avec Spring Data Redis (client Lettuce).
Rôle : `sortant-cache`. Le cache accélère des lectures ; il n'est **jamais** la source de vérité.

## Cas d'usage
- Lectures fréquentes et coûteuses dont le résultat change peu (catalogue, référentiels, résultats calculés).
- Données éphémères partagées entre instances : limitation de débit, verrous courts, sessions.
- **Ne pas** l'utiliser pour une donnée qui n'existe nulle part ailleurs (Redis peut perdre des données),
  ni comme file de messages fiable (préférer RabbitMQ ou Kafka). Un cache local (Caffeine) suffit si l'application
  n'a qu'une instance.

## Dépendances
- `spring-boot-starter-data-redis` (Lettuce inclus)
- Optionnel, pour `@Cacheable` : `spring-boot-starter-cache`
- Tests : `spring-boot-testcontainers`, `org.testcontainers:testcontainers-junit-jupiter`
  (conteneur générique `redis:7-alpine`, voir « Tests »)

## Connexion et configuration
```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}    # vide en local uniquement ; obligatoire hors poste de développement
      ssl:
        enabled: ${REDIS_SSL:false}
      timeout: 2s                     # délai maximal d'une commande
      connect-timeout: 2s
app:
  cache:
    ttl:
      default: 10m                    # TTL obligatoire, par type de donnée si besoin
```
- Sérialisation JSON des valeurs (sérialiseur JSON de Spring Data Redis compatible avec la version de Jackson du projet ;
  à vérifier au premier usage), clés en `String`. Jamais la sérialisation Java native.

## Rôle dans l'architecture
- `sortant-cache` : un adapter de cache qui implémente un port de sortie (hexagonal) ou un composant `cache` appelé
  par le service (couches). Deux façons de faire :
  - **décorateur** : l'adapter de cache implémente le même port que l'adapter de persistance et l'enveloppe
    (lecture dans le cache, sinon dans la base puis écriture dans le cache) ;
  - **`@Cacheable` / `@CacheEvict`** sur les méthodes de l'adapter de persistance (rôle `sortant-persistance`),
    jamais sur le domaine ni sur un use case.
- La configuration (`RedisCacheManager`, `RedisTemplate`, TTL) va dans un package `config`.

## Conventions
- Clés : `<application>:<module>:<objet>:<identifiant>[:v<n>]` (ex. `library:catalog:book:9782070612758:v1`).
  Changer le suffixe de version quand le format de la valeur change, au lieu de vider le cache.
- **TTL obligatoire** sur chaque clé ; aucune clé sans expiration.
- Invalidation explicite à chaque écriture de la donnée (`@CacheEvict` ou suppression de la clé dans le même cas d'utilisation,
  après la validation de la transaction).
- Valeurs : DTO de cache sérialisables (`record`), jamais d'entité JPA ni d'objet du domaine avec comportement.

## Résilience
- **Redis indisponible ne doit jamais faire échouer une requête** : en cas d'erreur, lire dans la base et journaliser
  un avertissement (avec `@Cacheable` : un `CacheErrorHandler` qui ignore les erreurs de lecture et d'écriture).
- Timeouts courts (`timeout`, `connect-timeout`) pour ne pas bloquer les threads.
- Pas de cohérence forte : accepter qu'une donnée en cache ait jusqu'à un TTL de retard, et le dire dans la spec.

## Sécurité
- Mot de passe (ou ACL utilisateur Redis 6+) et TLS hors poste de développement.
- Jamais de donnée personnelle sensible ni de token en clair dans une valeur de cache sans TTL court.
- Ne pas exposer le port Redis sur le réseau public.

## Observabilité
- Health check Actuator `redis` (inclus avec le starter).
- Métriques de cache (`cache.gets`, `cache.puts`, taux de succès) si `spring-boot-starter-cache` et Micrometer sont présents.
- Journaliser en `warn` les erreurs de cache ignorées, sans la valeur.

## Développement local
- **Windows** : pas de Redis natif ; conteneur `docker run -d --name redis -p 6379:6379 redis:7-alpine`
  (ou un équivalent compatible installé localement).
- **macOS** : `brew install redis` puis `brew services start redis`, ou le même conteneur.
- **Linux** : paquet `redis-server`, ou le même conteneur.

## Tests
```java
@Bean
@ServiceConnection(name = "redis")
GenericContainer<?> redisContainer() {
    return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
}
```
- Test de l'adapter de cache : première lecture → base appelée ; seconde lecture → base non appelée ; écriture → clé invalidée.
- Test de résilience : Redis arrêté (ou client simulé en erreur) → la lecture réussit depuis la base.
- Test du TTL : la clé écrite a bien une expiration (`TTL` > 0).

## Grille de revue
- Aucune classe Redis hors du rôle `sortant-cache` et de `config` ; domaine et use cases sans annotation de cache.
- Chaque clé a un TTL ; nommage `<application>:<module>:<objet>:<id>` ; invalidation à chaque écriture.
- Erreur de cache sans effet sur la réponse (repli sur la base) et testée.
- Pas d'entité JPA ni de donnée sensible sérialisée ; sérialisation JSON, pas Java native.
