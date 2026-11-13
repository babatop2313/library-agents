# Option `lombok` — usage de Lombok

Clé dans `.claude/stack.json` : `"conventions": { "lombok": "<valeur>" }`.
Dans tous les cas, la référence d'architecture l'emporte : si elle interdit Lombok dans une couche (le domaine en
hexagonal, par exemple), il y est interdit.

## Valeur : interdit
- Aucune annotation Lombok. Les objets de valeur et les DTO sont des `record`, les entités ont des constructeurs explicites.
- Retirer la dépendance `lombok` du `pom.xml` si elle est présente.

## Valeur : hors-domaine
- Lombok est autorisé **hors du domaine** (entités JPA, configuration, adapters) : `@Getter`, `@RequiredArgsConstructor`,
  `@Slf4j`, `@Builder` si utile.
- Interdits partout : `@Data` sur une entité JPA (equals/hashCode sur des champs modifiables), `@SneakyThrows`,
  `@Setter` sur une classe qui porte des invariants.
- Préférer les `record` pour les DTO, même avec Lombok disponible.
