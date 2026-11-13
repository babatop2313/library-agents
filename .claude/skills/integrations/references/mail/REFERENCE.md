# Intégration — Mail (SMTP)
> Statut : non testée

## Résumé
Envoi d'e-mails transactionnels (confirmation, rappel, alerte) par SMTP avec `JavaMailSender`.
Rôle : `sortant-notification`. L'envoi se fait toujours **après** la validation de la transaction métier.

## Cas d'usage
- Notifications ponctuelles à un utilisateur : confirmation d'action, rappel d'échéance, réinitialisation de mot de passe.
- **Ne pas** l'utiliser pour des envois massifs ou marketing (préférer un service d'e-mailing dédié avec API),
  ni pour une notification qui doit arriver en temps réel dans l'application (préférer WebSocket ou SSE).

## Dépendances
- `spring-boot-starter-mail`
- Optionnel, pour des modèles HTML : `spring-boot-starter-thymeleaf`
- Tests : `com.icegreen:greenmail-junit5` (serveur SMTP en mémoire), ou un conteneur Mailpit

## Connexion et configuration
```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}       # vide en local (Mailpit) ; obligatoire hors poste de développement
    properties:
      mail.smtp.auth: ${MAIL_SMTP_AUTH:false}
      mail.smtp.starttls.enable: ${MAIL_STARTTLS:false}
      mail.smtp.connectiontimeout: 5000
      mail.smtp.timeout: 5000
      mail.smtp.writetimeout: 5000
app:
  mail:
    from: ${MAIL_FROM:no-reply@localhost}
```

## Rôle dans l'architecture
- `sortant-notification` : un adapter qui implémente un port de sortie métier (`NotifyMemberPort.sendLoanReminder(...)`),
  pas un port technique « envoyer un mail ». Le domaine exprime l'intention, l'adapter choisit le canal.
- Les modèles de message (texte ou HTML) sont des ressources (`src/main/resources/templates/mail/`).

## Conventions
- Un modèle par type de message ; sujet et corps en français ; adresse d'expéditeur configurée, jamais en dur.
- Le contenu ne contient que le nécessaire (pas de donnée sensible) ; liens vers l'application plutôt que des données.
- Identifiant de message (UUID) dans un en-tête ou un journal d'envoi pour tracer sans exposer l'adresse.

## Résilience
- **Jamais d'envoi dans la transaction** : publier un événement métier, puis envoyer dans un
  `@TransactionalEventListener(phase = AFTER_COMMIT)`, de préférence asynchrone (`@Async`).
- Un échec d'envoi ne fait pas échouer l'action métier : journaliser, réessayer un nombre limité de fois.
- Si la perte d'un message est inacceptable : table « outbox » écrite dans la transaction, envoyée par une tâche planifiée.

## Sécurité
- Identifiants SMTP en variables d'environnement ; STARTTLS ou SMTPS hors poste de développement.
- Jamais d'adresse e-mail complète ni de contenu de message dans les logs (masquer : `b***@exemple.com`).
- Échapper toute donnée utilisateur insérée dans un modèle HTML (Thymeleaf le fait par défaut).

## Observabilité
- Health check Actuator `mail` (inclus avec le starter ; désactivable s'il ralentit le démarrage).
- Compteurs d'envois réussis et échoués (Micrometer).

## Développement local
- **Mailpit**, qui capture les messages sans les envoyer : `docker run -d --name mailpit -p 1025:1025 -p 8025:8025 axllent/mailpit`,
  puis lire les messages sur `http://localhost:8025`. Fonctionne sous Windows, macOS et Linux.
- Ne jamais configurer un vrai serveur SMTP sur un poste de développement.

## Tests
- Adapter : GreenMail (`@RegisterExtension GreenMailExtension`) ; vérifier destinataire, sujet et contenu du message reçu.
- Cas d'utilisation : port de notification mocké ; vérifier qu'il n'est appelé qu'après le succès de l'action.
- Échec SMTP simulé : l'action métier réussit quand même.

## Grille de revue
- Aucun usage de `JavaMailSender` hors du rôle `sortant-notification` et de `config`.
- Envoi après commit, jamais dans la transaction ; échec sans effet sur l'action métier.
- Timeouts SMTP présents ; identifiants en variables d'environnement.
- Pas d'adresse ni de contenu dans les logs ; données utilisateur échappées dans les modèles.
