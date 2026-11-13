---
description: Ajoute une technologie au backend (redis, kafka, rabbitmq, mail, stockage-objet…) à partir de sa référence d'intégration — dépendances, configuration, Testcontainers, santé — et la déclare dans .claude/stack.json. Ne crée aucun code métier.
argument-hint: <techno>   (nom d'un dossier de .claude/skills/integrations/references/)
---

Objectif : brancher la technologie **$ARGUMENTS** au backend, prête à être utilisée par une feature, **sans écrire de code métier**.
Le code qui l'utilise (adapter de cache, consommateur, envoi de mail…) se crée ensuite avec `/feature`, à partir d'une spec.

Crée et modifie les fichiers avec Write et Edit uniquement ; respecte l'architecture active et les conventions.

1. **Vérifications** (sans rien modifier)
   - `.claude/skills/integrations/references/$ARGUMENTS/REFERENCE.md` existe. Sinon, liste les références disponibles
     et **arrête-toi** (une nouvelle référence s'écrit d'abord dans le kit, à partir de `_template`).
   - Lis `.claude/stack.json` avec Read. Si `$ARGUMENTS` figure déjà dans `integrations` : **arrête-toi**, rien à faire.
   - Lis la référence **en entier** et note son **statut**. Si elle est « non testée », annonce-le avant de continuer :
     noms d'artefacts et de propriétés à vérifier dans la documentation officielle.
   - Lis `.claude/skills/architecture/references/<architecture>/roles.conf` : rôles que jouera l'intégration et packages associés.
   - Point de départ, comme l'étape 1 de `/feature` : aucune modification non commitée, branche `baseBranch` à jour.
     Puis crée `chore/integration-$ARGUMENTS`.

2. **Dépendances** (`backend/pom.xml`) : celles de la section « Dépendances », sans version quand le BOM Spring Boot
   la gère. Vérifie la version de Spring Boot du projet ; si une dépendance de la référence ne correspond pas à cette
   version, arrête-toi et signale-le. Puis `./mvnw -q dependency:tree` (depuis `backend/`) : signale tout conflit.

3. **Configuration** : le bloc de la section « Connexion et configuration » dans `application.yml`, avec des `${VARIABLES}`.
   Aucune valeur par défaut pour un secret. Les beans techniques éventuels (client, gestionnaire d'erreurs, TTL…)
   vont dans un package `config` (global ou du module, selon la référence d'architecture).
   Ajoute les nouvelles variables à `.env.example` s'il existe (noms et valeurs locales d'exemple, jamais de vrai secret).

4. **Tests** : la configuration Testcontainers de la section « Tests » (dans la configuration de test existante,
   par exemple `TestcontainersConfiguration`), et un test qui prouve que le contexte démarre avec l'intégration
   et que son indicateur de santé est `UP` quand il existe.

5. **Déclaration** : ajoute `$ARGUMENTS` au tableau `integrations` de `.claude/stack.json` avec Edit
   (une règle de permission demande mon accord : c'est voulu).

6. **Contrôle** : `./mvnw clean test` (depuis `backend/` ; Docker doit tourner). Tout doit être vert,
   `ArchitectureTest` compris, sans modifier ses règles.

7. **Clôture** : commit `chore(<integration>): branchement de $ARGUMENTS`, puis donne-moi :
   - le statut de la référence (testée / non testée) et ce que tu as dû vérifier ou ajuster ;
   - les fichiers créés ou modifiés, les variables d'environnement à définir ;
   - la procédure de développement local (section « Développement local »), pour Windows, macOS et Linux ;
   - la suite : la feature qui l'utilisera, à lancer avec `/feature`.
   Si la référence était « non testée » et que tout a fonctionné, propose de la passer à « testée » **dans le kit**
   (`factory-dev`), avec les ajustements découverts : jamais dans la copie du projet, remplacée à chaque mise à jour.

Ne pousse rien et n'ouvre pas de PR/MR sans mon accord.
