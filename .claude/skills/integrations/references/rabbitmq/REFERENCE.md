# Intégration — RabbitMQ
> Statut : non testée

## Résumé
Courtier de messages (AMQP) pour communiquer de façon asynchrone entre modules ou services, avec Spring AMQP.
Rôles : `sortant-messagerie` (publication) et `entrant-messagerie` (consommation).

## Cas d'usage
- Traitements asynchrones (tâches à faire plus tard), notifications entre services, routage fin des messages par clé.
- **Ne pas** l'utiliser pour rejouer l'historique des événements ou pour de très gros volumes en flux continu
  (préférer Kafka), ni entre deux modules d'une même application quand un appel direct par leur API suffit.

## Dépendances
- `spring-boot-starter-amqp`
- Tests : `spring-boot-testcontainers`, `org.testcontainers:testcontainers-rabbitmq`, `org.testcontainers:testcontainers-junit-jupiter`

## Connexion et configuration
```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}    # jamais de valeur par défaut
    virtual-host: ${RABBITMQ_VHOST:/}
    connection-timeout: 5s
    publisher-confirm-type: correlated
    publisher-returns: true
    listener:
      simple:
        acknowledge-mode: auto
        default-requeue-rejected: false   # un message en échec part dans la file d'erreurs, pas en boucle
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1s
          multiplier: 2
```
- Échanges, files et liaisons déclarés comme beans (`TopicExchange`, `Queue`, `Binding`) dans `config`.
- Messages en JSON (`Jackson`), jamais la sérialisation Java native.

## Rôle dans l'architecture
- `sortant-messagerie` : un adapter qui implémente un port de sortie de publication d'événements métier
  (`CatalogEventsPort.bookRegistered(...)`) et convertit l'événement en message.
- `entrant-messagerie` : un consommateur (`@RabbitListener`) qui convertit le message et appelle un port d'entrée
  (ou le service), **exactement comme un contrôleur** : aucune logique métier dans le consommateur.

## Conventions
- Échange de type *topic* par domaine : `<domaine>.events` (ex. `catalog.events`).
- Clé de routage : `<objet>.<evenement>.v<n>` (ex. `book.registered.v1`).
- File : `<service-consommateur>.<objet>.<evenement>` ; file d'erreurs associée : `<file>.dlq`.
- Message : `eventId` (UUID), `occurredAt` (ISO-8601, UTC), `version`, puis les données utiles. Jamais d'entité.
- Une évolution incompatible du message crée une nouvelle version de clé de routage (`v2`), l'ancienne est maintenue un temps.

## Résilience
- **Publication cohérente avec la base** : publier après la validation de la transaction
  (`@TransactionalEventListener(phase = AFTER_COMMIT)`), ou table « outbox » si aucune perte n'est acceptable.
- **Consommateur idempotent** : un même message peut arriver deux fois ; mémoriser les `eventId` traités
  (contrainte d'unicité) ou rendre le traitement naturellement idempotent.
- Retry limité puis file d'erreurs (`.dlq`) surveillée ; jamais de remise en file infinie.

## Sécurité
- Utilisateur et vhost dédiés à l'application, droits limités à ses échanges et files ; TLS hors poste de développement.
- Pas de donnée sensible dans un message sans nécessité ; jamais de token.
- Valider le contenu d'un message reçu comme une entrée d'API (il vient de l'extérieur du module).

## Observabilité
- Health check Actuator `rabbit` (inclus avec le starter).
- Surveiller la profondeur des files et des `.dlq` (console d'administration ou métriques Micrometer).
- Journaliser `eventId` et clé de routage, jamais le contenu complet du message.

## Développement local
- `docker run -d --name rabbitmq -p 5672:5672 -p 15672:15672 rabbitmq:4-management`,
  console sur `http://localhost:15672` (identifiants par défaut `guest` / `guest`, uniquement en local).
  Fonctionne sous Windows, macOS et Linux.

## Tests
- Conteneur `RabbitMQContainer("rabbitmq:4-management")` avec `@ServiceConnection`.
- Publication : l'événement métier produit un message sur la bonne clé de routage, avec `eventId` et `version`.
- Consommation : un message reçu appelle le port d'entrée ; reçu deux fois, il n'est traité qu'une fois ;
  en échec répété, il arrive dans la `.dlq`.

## Grille de revue
- Aucune classe `org.springframework.amqp` ou `com.rabbitmq` hors des rôles de messagerie et de `config`.
- Consommateur sans logique métier, idempotent, avec retry limité et file d'erreurs.
- Publication après commit (ou outbox) ; messages JSON versionnés avec `eventId`.
- Nommage des échanges, clés et files conforme ; identifiants en variables d'environnement.
