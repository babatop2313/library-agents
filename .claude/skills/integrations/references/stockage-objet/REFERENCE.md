# Intégration — Stockage objet (S3 / MinIO)
> Statut : non testée

## Résumé
Stockage de fichiers (documents, images) dans un service compatible S3 : Amazon S3 en production, MinIO en local.
Rôle : `sortant-fichiers`. La base ne garde que la **référence** du fichier (clé, type, taille), jamais son contenu.

## Cas d'usage
- Fichiers envoyés par les utilisateurs, documents générés (PDF), images.
- **Ne pas** stocker de fichier dans PostgreSQL (`bytea`) au-delà de quelques Ko, ni sur le disque du serveur
  d'application (perdu au redéploiement, non partagé entre instances).

## Dépendances
- AWS SDK v2 : BOM `software.amazon.awssdk:bom` dans `dependencyManagement`, puis `software.amazon.awssdk:s3`
  (compatible MinIO). Alternative : Spring Cloud AWS (`io.awspring.cloud:spring-cloud-aws-starter-s3`).
- Tests : `org.testcontainers:testcontainers-minio`, `org.testcontainers:testcontainers-junit-jupiter`

## Connexion et configuration
```yaml
app:
  storage:
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}   # vide pour Amazon S3
    region: ${STORAGE_REGION:eu-west-3}
    bucket: ${STORAGE_BUCKET}
    access-key: ${STORAGE_ACCESS_KEY}
    secret-key: ${STORAGE_SECRET_KEY}                     # jamais de valeur par défaut
    path-style: ${STORAGE_PATH_STYLE:true}                # true pour MinIO
    max-size: 10MB
    presigned-url-ttl: 5m
```
- Le client `S3Client` est un bean de `config`, avec `forcePathStyle` pour MinIO et un `apiCallTimeout` explicite
  (`ClientOverrideConfiguration`).

## Rôle dans l'architecture
- `sortant-fichiers` : un adapter qui implémente un port de sortie (`DocumentStoragePort.store(...)`, `urlFor(...)`).
- Le domaine manipule une référence (`DocumentRef` : clé, type MIME, taille), jamais un `S3Client` ni un flux S3.

## Conventions
- Clé d'objet : `<module>/<objet>/<identifiant>/<uuid>.<extension>` ; **jamais** le nom de fichier fourni par l'utilisateur
  comme clé (il est conservé à part, nettoyé, pour l'affichage).
- Type MIME vérifié côté serveur (liste blanche) et taille limitée avant l'envoi.
- Téléchargement par **URL pré-signée** à durée courte, plutôt que de faire transiter le fichier par l'application.
- Un bucket par application et par environnement ; accès public désactivé.

## Résilience
- Ordre : envoyer le fichier, **puis** enregistrer la référence en base ; si l'enregistrement échoue, supprimer l'objet.
  Une tâche planifiée peut nettoyer les objets orphelins.
- Timeouts explicites ; un stockage indisponible renvoie une erreur claire (503), sans bloquer les threads.
- Envois volumineux : multipart, sans charger tout le fichier en mémoire.

## Sécurité
- Clés d'accès en variables d'environnement (ou rôle IAM en production) ; droits limités au bucket de l'application.
- Bucket privé ; chiffrement côté serveur activé.
- Vérifier que l'utilisateur a le droit de lire un fichier **avant** de générer son URL pré-signée (pas d'IDOR).

## Observabilité
- Indicateur de santé maison (`HealthIndicator` qui vérifie l'accès au bucket), car aucun n'est fourni par défaut.
- Journaliser la clé et la taille, jamais le contenu ni le nom de fichier d'origine s'il est personnel.

## Développement local
- **MinIO** : `docker run -d --name minio -p 9000:9000 -p 9001:9001 -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 minio/minio server /data --console-address ":9001"`,
  console sur `http://localhost:9001` ; créer le bucket. Fonctionne sous Windows, macOS et Linux.

## Tests
- Adapter : conteneur `MinIOContainer` ; envoyer, relire, supprimer ; vérifier la clé générée et le type MIME.
- Cas d'utilisation : port de stockage mocké ; vérifier la suppression de l'objet si l'enregistrement en base échoue.
- Refus d'un type MIME interdit et d'un fichier trop gros.

## Grille de revue
- Aucune classe `software.amazon.awssdk` hors du rôle `sortant-fichiers` et de `config`.
- Clé générée par l'application ; type et taille vérifiés ; URL pré-signée à durée courte, après contrôle d'accès.
- Pas de fichier en base ni sur le disque du serveur ; nettoyage des orphelins prévu.
- Clés d'accès en variables d'environnement ; bucket privé.
