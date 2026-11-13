# Option `mapping` — technique de conversion entre objets

Clé dans `.claude/stack.json` : `"conventions": { "mapping": "<valeur>" }`.

## Valeur : manuel
- Une classe `XxxMapper` par frontière (web, persistance, MCP…), avec des méthodes statiques ou un `@Component`
  sans état : `toDomain(...)`, `toEntity(...)`, `toResponse(...)`.
- Aucune dépendance supplémentaire. Le code est lisible et débogable ; le compilateur signale les champs oubliés
  si l'on construit des `record` par leur constructeur canonique.
- Recommandé quand les objets sont petits et peu nombreux.

## Valeur : mapstruct
- Dépendances `org.mapstruct:mapstruct` et le processeur d'annotations `org.mapstruct:mapstruct-processor`
  (dans la configuration de `maven-compiler-plugin`, après Lombok si Lombok est utilisé).
- `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)` : un champ oublié fait échouer la compilation.
- Les mappers restent dans le package que la référence d'architecture indique ; le domaine ne dépend jamais de MapStruct.
- Recommandé quand les objets sont nombreux ou volumineux.
