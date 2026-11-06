# Revue de MR — `test/piege-reviewer` → `feature/enregistrement-livre`

- **Branche revue** : `test/piege-reviewer` (commit `a038e16` « test: défauts volontaires pour la revue »)
- **Branche cible** : `feature/enregistrement-livre`
- **Fichier modifié** : `backend/src/main/java/com/example/library/catalog/adapter/in/web/BookController.java` (+26 / −3)
- **Spec de référence** : `docs/specs/catalog-enregistrement-livre.md`
- **Architecture vérifiée** : hexagonal (`.claude/skills/architecture/references/hexagonal/REFERENCE.md`)
- **Revue réalisée par** : agent `reviewer` (lecture seule)

## Verdict

❌ **CHANGEMENTS REQUIS**

`ArchitectureTest` est rouge et les 41 tests de `BookControllerTest` sont en erreur. Une fois la MR fusionnée, plus aucune requête `POST /api/v1/books` ne pourrait réussir.

## Vérifications lancées (lecture seule)

- `./mvnw -q compile` : OK. Le double import de `UUID` est accepté par javac car c'est le même type.
- `ArchitectureTest` : KO, 1 échec sur 4. La règle `inbound_adapters_use_input_ports_only` est violée 3 fois, à cause de `CheckIsbnRegisteredPort` dans `BookController`.
- `BookControllerTest` : KO, 41 erreurs sur 41. Le contexte Spring ne démarre pas : `NoSuchBeanDefinitionException: CheckIsbnRegisteredPort`, car la slice `@WebMvcTest` ne fournit aucun port de sortie.
- `ArchitectureTest` lui-même est présent, commence bien par `// architecture: hexagonal` et n'est pas modifié par le diff (un seul fichier touché).

## Remarques

Toutes les lignes ci-dessous concernent `backend/src/main/java/com/example/library/catalog/adapter/in/web/BookController.java`.

| Sévérité | Couche | Fichier:ligne | Problème | Correction proposée |
|---|---|---|---|---|
| BLOQUANT | adapter.in.web | BookController.java:7, 30, 33, 35 | **Règle de dépendance violée.** Le contrôleur importe et injecte un port de sortie (`application.port.out.CheckIsbnRegisteredPort`). La référence l'interdit : `adapter.in` ne doit jamais dépendre de `application.port.out`, et la grille de revue cite « Contrôleur qui injecte […] un port de sortie ». C'est ce qui fait échouer `ArchitectureTest`. | Retirer l'import, le champ et le paramètre du constructeur. Le contrôleur n'injecte que `RegisterBookUseCase`. |
| BLOQUANT | adapter.in.web | BookController.java:43-45 | **Règle métier hors du domaine.** Le contrôle d'unicité (RM-07 / RM-CAT-04) est refait dans le contrôleur. Or cette règle vit déjà dans `BookRegistrationPolicy.ensureIsbnIsFree`, appelée par `RegisterBookService` (RM-CAT-11). | Supprimer le bloc `if`. Le use case lève déjà `IsbnAlreadyExistsException`, que `CatalogExceptionHandler` traduit en 409. |
| BLOQUANT | adapter.in.web | BookController.java:43 | **Fonctionnalité cassée.** `Isbn.of("dkkdkdk")` est une valeur codée en dur et non valide : elle lève `InvalidIsbnException` à chaque appel. Toute requête valide d'un LIBRARIAN reçoit donc 400 `ISBN_INVALID` (`CatalogExceptionHandler:66-68`) au lieu de 201. Les scénarios « Enregistrement nominal », « ISBN avec tirets normalisé », « bornes valides », etc. échouent tous. Le commentaire « ouvre l'interface pour la signature exacte » est un reste de brouillon. | Supprimer la ligne (voir point précédent). |
| BLOQUANT | adapter.in.web (sécurité) | BookController.java:42 | **Fuite de secret dans les logs.** `jwt.getTokenValue()` écrit le Bearer token complet en INFO. N'importe qui ayant accès aux logs peut rejouer la session d'un bibliothécaire jusqu'à expiration du token. La grille interdit toute donnée sensible dans les logs. La spec exclut aussi « audit et journalisation métier » du périmètre. | Supprimer la ligne et le logger (l. 18-19, 28). Si une trace est vraiment utile, logger au plus `jwt.getSubject()` en DEBUG, jamais le token. |
| BLOQUANT | adapter.in.web (sécurité) | BookController.java:50-53 | **Endpoint factice sans contrôle d'accès.** `DELETE /api/v1/books/{id}` n'a pas de `@PreAuthorize`. `SecurityConfig` ne le couvre que par `anyRequest().authenticated()`, donc un simple MEMBER obtient 204. Il ne supprime rien, mais il annonce un faux succès au client. La spec range aussi la suppression dans le « Hors périmètre ». | Supprimer la méthode et ses imports (`DeleteMapping`, `PathVariable`, `UUID`). La suppression fera l'objet de sa propre spec, avec `@PreAuthorize("hasRole('LIBRARIAN')")`, un use case et des tests. |
| MAJEUR | adapter.in.web | BookController.java:44 | `ResponseEntity.status(409).build()` renvoie un corps vide. Le contrat de la spec exige un `ProblemDetail` avec `code = ISBN_ALREADY_EXISTS`. Ce contrôle n'est pas non plus dans la transaction `@UseCase` : entre la vérification et l'enregistrement, une autre requête peut insérer le même ISBN. | Laisser le use case et `CatalogExceptionHandler` gérer le 409, comme avant le diff. |
| MAJEUR | tests | BookControllerTest (contexte) | Le diff casse les 41 tests existants : aucun `@MockitoBean` ne fournit `CheckIsbnRegisteredPort`. Ajouter ce mock serait une erreur, car cela validerait la violation d'architecture. Aucun test ne couvre non plus le nouvel endpoint DELETE ni son contrôle d'accès. | Corriger le code de production (points ci-dessus). Les tests repasseront sans modification. |
| MINEUR | adapter.in.web | BookController.java:4 et 24 | `import java.util.UUID;` apparaît deux fois. Cela compile, mais c'est du bruit. Il deviendra un import inutilisé une fois DELETE supprimé. | Supprimer les deux lignes (4 et 24). |
| MINEUR | adapter.in.web | BookController.java:18-24 | Ce second bloc d'imports est hors de l'ordre habituel, mélange `java.*` et `org.*`, et n'a pas de ligne vide avant `@RestController` (l. 25). | Supprimer les imports devenus inutiles et revenir au regroupement d'origine. |
| MINEUR | adapter.in.web | BookController.java:51 | La méthode `delete` est `public` alors que la classe et `register` sont package-private. La référence demande des adapters package-private quand c'est possible. | Sans objet une fois la méthode supprimée. |
| SUGGESTION | adapter.in.web | BookController.java:30 / 28-29 | Double espace dans `private  final`, et la ligne vide après l'accolade de classe a été supprimée. | Revenir à la mise en forme d'origine. |
| SUGGESTION | adapter.in.web | BookController.java:41 | Le paramètre `@AuthenticationPrincipal Jwt jwt` n'a pas d'usage métier : la spec ne demande aucun audit. | Le retirer avec la ligne 42. |

## Conformité à la spec `docs/specs/catalog-enregistrement-livre.md`

- **Ordre 401 → 403 → 400 → 409** : 401 et 403 restent assurés par `SecurityConfig` (`POST /api/v1/books` → `hasRole("LIBRARIAN")`) et par `@PreAuthorize`. La validation `@Valid` s'exécute toujours avant le corps de la méthode. En revanche, l'étape suivante produit toujours un 400 erroné (l. 43), si bien que 201 et 409 ne sont plus jamais atteints.
- **Contrat `POST /api/v1/books`** : cassé. Plus de 201, et un 409 sans `ProblemDetail`.
- **Hors périmètre** : non respecté. Le DELETE et la journalisation sont explicitement exclus.

## Points positifs

- Le diff ne touche qu'un seul fichier, sans migration ni schéma modifié.
- `ArchitectureTest` est intact et remplit son rôle, puisqu'il détecte la violation.
- Le `@PreAuthorize("hasRole('LIBRARIAN')")` de `register` et le `@Valid` sont conservés.
- Le code de la branche cible (`RegisterBookService`, `BookRegistrationPolicy`, `CatalogExceptionHandler`) gère déjà correctement RM-07 / RM-CAT-04 et le 409 : la bonne correction consiste à **annuler entièrement ce diff**.

## Synthèse

**CHANGEMENTS REQUIS.** Il y a 5 BLOQUANTS :

1. la violation de la règle de dépendance ;
2. la règle métier dans le contrôleur ;
3. la fonctionnalité cassée ;
4. le token dans les logs ;
5. l'endpoint DELETE non protégé et hors périmètre.

## Proposition de commentaire de MR

> ❌ **Changements requis.**
> Cette MR casse `ArchitectureTest` (le contrôleur injecte un port de sortie, `CheckIsbnRegisteredPort`) et les 41 tests de `BookControllerTest`. Elle rend aussi `POST /api/v1/books` inutilisable : `Isbn.of("dkkdkdk")` provoque un 400 à chaque appel.
> Deux problèmes de sécurité bloquants :
> - le JWT complet est écrit dans les logs ;
> - l'endpoint `DELETE /{id}` est accessible à un simple MEMBER, alors qu'il ne supprime rien et sort du périmètre de la spec.
>
> L'unicité de l'ISBN et la réponse 409 sont déjà gérées par le use case et `CatalogExceptionHandler`. Merci d'annuler ce diff.
