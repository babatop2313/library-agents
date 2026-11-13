# Option `errors` — format des réponses d'erreur de l'API

Clé dans `.claude/stack.json` : `"conventions": { "errors": "<valeur>" }`.

## Valeur : problem-detail
- Un `@RestControllerAdvice` par module, dans la couche web, renvoie un `ProblemDetail` (RFC 9457) avec la propriété `code`
  (le code métier de l'exception) :
  - ressource introuvable → 404 ;
  - conflit (doublon, version concurrente) → 409 ;
  - règle métier violée → 422 ;
  - `MethodArgumentNotValidException` → 400 avec la liste des champs en erreur (`errors: [{field, message}]`) ;
  - accès refusé → 403 ; non authentifié → 401 (gérés par Spring Security).
- `title` lisible par un humain, `detail` sans donnée technique ni donnée personnelle.
