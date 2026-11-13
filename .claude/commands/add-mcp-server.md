---
description: Installe dans le backend l'infrastructure d'un serveur MCP (Spring AI, Streamable HTTP, profil mcp-local sécurisé). Ne crée aucun outil MCP : les outils passent ensuite par /feature.
argument-hint: (aucun argument)
---

Objectif : rendre le backend capable d'exposer des outils MCP à un assistant IA (Claude Code, Copilot, Gemini…),
**sans créer d'outil**. Les outils sont des adapters entrants : ils se créent ensuite avec `/feature`, à partir d'une spec.

Recette validée sur le projet Bibliothèque (factory v3.3). Travaille sur une branche dédiée, crée et modifie les fichiers
avec Write et Edit uniquement, et respecte l'architecture active.

1. **Prérequis**
   - Lis `.claude/factory.json` avec Read (architecture) et la référence active : section « Structure des packages »
     (emplacement des outils MCP) et sous-section MCP des « Exemples de code ».
   - Vérifie le point de départ comme l'étape 1 de `/feature` (aucune modification non commitée, branche `baseBranch` à jour),
     puis crée `chore/mcp-server`.
   - **Déjà installé ?** Si `pom.xml` contient `spring-ai-starter-mcp-server`, ou si une configuration de sécurité gère `/mcp` :
     arrête-toi, liste ce qui existe déjà, et ne modifie rien.

2. **Dépendances** (`backend/pom.xml`)
   - BOM `org.springframework.ai:spring-ai-bom` dans `dependencyManagement`, à la **dernière version 2.0.x** :
     vérifie-la (Maven Central ou documentation Spring AI) et sa compatibilité avec la version de Spring Boot du projet.
     Si tu ne peux pas la vérifier, arrête-toi et demande-la-moi.
   - Starter `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`, sans version (gérée par le BOM).
   - `./mvnw -q dependency:tree` (depuis `backend/`) : signale tout conflit de version (Jackson notamment).

3. **Configuration**
   - `application.yml` : nom et version du serveur, transport Streamable HTTP, endpoint `/mcp`, scan des annotations activé.
   - `application-mcp-local.yml` : `server.address: 127.0.0.1`. Ce profil n'est **jamais** activé par défaut
     (ni dans `application.yml`, ni dans un `spring.profiles.active`, ni dans le `pom.xml`).

4. **Sécurité** (dans le package de configuration globale)
   - **Sans** le profil `mcp-local` : `/mcp` exige un JWT valide, comme le reste de l'API (aucune règle `permitAll` pour `/mcp`).
   - **Avec** le profil `mcp-local`, une chaîne de sécurité dédiée à `/mcp` qui :
     - l'ouvre sans authentification ;
     - refuse (403) toute requête dont l'en-tête `Origin` est présent et n'est pas exactement `http://localhost` ou
       `http://127.0.0.1` (port facultatif), et toute requête dont l'en-tête `Host` n'est pas `localhost` ou `127.0.0.1`
       (protection contre le DNS rebinding) ; ce filtre passe avant les autres filtres de sécurité ;
     - ne change rien pour `/api/**`, qui exige toujours un JWT.
   - Un garde-fou au démarrage, actif avec le profil `mcp-local` :
     - journalise un avertissement (WARN) qui dit que `/mcp` est ouvert sans authentification et n'écoute qu'en local ;
     - **refuse de démarrer** si un profil actif s'appelle `prod` ;
     - **refuse de démarrer** si l'adresse **effective** du serveur n'est pas `127.0.0.1` ou `localhost`
       (elle peut être écrasée par la variable d'environnement `SERVER_ADDRESS`).

5. **Tests** (c'est toi qui les écris ici : il n'y a pas de logique métier)
   - Sans le profil : `/mcp` → 401 sans JWT, 401 avec un JWT invalide ou expiré ; `/mcp` accessible avec un JWT valide.
   - Avec le profil : `/mcp` accessible sans JWT ; `/api/**` → toujours 401 sans JWT ;
     `Origin` malveillant ou suffixé (`http://localhost.evil.com`, `null`, `https://…`) → 403 ; `Origin` local → accepté ;
     `Host` non local → 403.
   - Garde-fou : refus avec le profil `prod`, refus avec `server.address=0.0.0.0` ; démarrage accepté avec `127.0.0.1`.
   - `./mvnw clean test` (depuis `backend/`) : tout doit être vert, `ArchitectureTest` compris, **sans modifier ses règles**.

6. **Clôture** : commit `chore(mcp): infrastructure du serveur MCP (Spring AI, profil mcp-local)`, puis donne-moi :
   - la version de Spring AI retenue et pourquoi ;
   - les fichiers créés ou modifiés ;
   - le résultat des tests ;
   - la procédure de test manuel :
     ```
     # Terminal 1, depuis backend/
     ./mvnw spring-boot:run "-Dspring-boot.run.profiles=mcp-local"
     # Terminal 2, à la racine du projet
     claude mcp add --transport http <nom-du-projet> http://localhost:8080/mcp
     # puis, dans Claude Code : /mcp
     ```
   - la suite : créer le premier outil avec `/feature`, en précisant qu'il s'agit d'un outil MCP en lecture seule.

Ne pousse rien et n'ouvre pas de PR/MR sans mon accord.
