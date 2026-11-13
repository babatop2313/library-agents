# Intégration — Kafka
> Statut : non testée

## Résumé
Journal d'événements distribué pour publier et consommer des événements métier entre services, avec Spring for Apache Kafka.
Rôles : `sortant-messagerie` (producteur) et `entrant-messagerie` (consommateur).

## Cas d'usage
- Diffusion d'événements métier à plusieurs consommateurs, rejeu de l'historique, gros volumes, ordre garanti par clé.
- **Ne pas** l'utiliser pour de simples tâches asynchrones ou du routage fin (RabbitMQ est plus simple), ni pour
  un appel qui attend une réponse immédiate (appel HTTP ou API de module).

## Dépendances
- `spring-boot-starter-kafka` (Spring Boot 4 ; avec Spring Boot 3 : `org.springframework.kafka:spring-kafka`). À vérifier au premier usage.
- Tests : `spring-boot-testcontainers`, `org.testcontainers:testcontainers-kafka`, `org.testcontainers:testcontainers-junit-jupiter`

## Connexion et configuration
```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 30000
    consumer:
      group-id: ${KAFKA_GROUP_ID:<application>}
      auto-offset-reset: earliest
      properties:
        isolation.level: read_committed
    properties:
      security.protocol: ${KAFKA_SECURITY_PROTOCOL:PLAINTEXT}   # SASL_SSL hors poste de développement
```
- Sérialisation JSON des valeurs, clé en `String` ; types de message explicites (pas de désérialisation de classes arbitraires).
- `DefaultErrorHandler` avec `DeadLetterPublishingRecoverer` et un `BackOff` limité, déclaré dans `config`.

## Rôle dans l'architecture
- `sortant-messagerie` : un adapter qui implémente un port de sortie de publication d'événements métier
  et produit le message (`KafkaTemplate`).
- `entrant-messagerie` : un consommateur (`@KafkaListener`) qui convertit le message et appelle un port d'entrée
  (ou le service), **exactement comme un contrôleur** : aucune logique métier dans le consommateur.

## Conventions
- Topic : `<domaine>.<objet>.<evenement>.v<n>` (ex. `catalog.book.registered.v1`) ; topic d'erreurs : `<topic>.DLT`.
- **Clé du message = identifiant de l'agrégat** (ex. ISBN) : garantit l'ordre des événements d'un même objet.
- Message : `eventId` (UUID), `occurredAt` (ISO-8601, UTC), `version`, puis les données utiles. Jamais d'entité.
- Un schéma publié ne change jamais de façon incompatible : nouveau topic `v<n+1>`, l'ancien est maintenu un temps.
- Groupe de consommateurs : nom de l'application consommatrice.

## Résilience
- **Cohérence avec la base** : table « outbox » écrite dans la transaction métier puis publiée, ou publication
  après commit si la perte rare d'un événement est acceptable (à écrire dans la spec).
- **Consommateur idempotent** (livraison « au moins une fois ») : mémoriser les `eventId` traités ou rendre le traitement idempotent.
- Retry limité avec délai croissant, puis topic `.DLT` surveillé ; un message empoisonné ne bloque pas la partition.

## Sécurité
- SASL/SSL et ACL par topic hors poste de développement ; identifiants en variables d'environnement.
- Pas de donnée sensible dans un événement sans nécessité (un événement est conservé et rejouable).
- Valider le contenu d'un message reçu comme une entrée d'API.

## Observabilité
- Aucun health check Kafka n'est fourni par défaut : surveiller le retard des consommateurs (*consumer lag*)
  et les métriques Micrometer du client Kafka.
- Journaliser `eventId`, topic, partition et offset, jamais le contenu complet du message.

## Développement local
- Kafka en mode KRaft (sans ZooKeeper) : `docker run -d --name kafka -p 9092:9092 apache/kafka:latest`
  (fixer une version précise dans le projet). Fonctionne sous Windows, macOS et Linux.

## Tests
- Conteneur `org.testcontainers.kafka.KafkaContainer("apache/kafka:<version>")` avec `@ServiceConnection`.
- Production : l'événement métier produit un message sur le bon topic, avec la bonne clé, `eventId` et `version`.
- Consommation : un message reçu appelle le port d'entrée ; reçu deux fois, il n'est traité qu'une fois ;
  en échec répété, il arrive dans le `.DLT`. Attendre les messages avec Awaitility, jamais `Thread.sleep`.

## Grille de revue
- Aucune classe `org.springframework.kafka` ou `org.apache.kafka` hors des rôles de messagerie et de `config`.
- Clé = identifiant de l'agrégat ; topic et message versionnés ; `eventId` présent.
- Producteur idempotent (`acks=all`) ; consommateur idempotent ; retry limité et `.DLT`.
- Stratégie de cohérence avec la base explicite (outbox ou après commit) ; sécurité configurée hors local.
