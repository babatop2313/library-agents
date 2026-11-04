---
name: angular-conventions
description: Conventions Angular de l'équipe (structure par feature, composants standalone, signals, services HTTP, formulaires, gestion d'erreurs). À charger avant d'écrire ou de relire du code frontend.
---

# Conventions Angular

## Structure
```
src/app/
  core/        intercepteurs (JWT, erreurs), guards, services transverses
  shared/      composants/pipes réutilisables, sans dépendance métier
  features/<feature>/
    <feature>.routes.ts
    data/      <feature>.model.ts, <feature>.service.ts
    pages/     composants routés (list, detail, form)
    ui/        composants de présentation (inputs/outputs uniquement)
```

## Composants
- `standalone: true`, `changeDetection: ChangeDetectionStrategy.OnPush`.
- État local en `signal()` / `computed()` ; `input()` et `output()` plutôt que les décorateurs.
- Fichiers séparés `.ts` / `.html` / `.scss` dès que le template dépasse 15 lignes.

## Services HTTP
```ts
@Injectable({ providedIn: 'root' })
export class CategoryService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/categories`;

  list(page = 0, size = 20) {
    return this.http.get<Page<Category>>(this.baseUrl, { params: { page, size } });
  }
  create(body: CategoryCreateRequest) {
    return this.http.post<Category>(this.baseUrl, body);
  }
}
```

## Formulaires
- Formulaires réactifs typés (`FormBuilder.nonNullable`).
- Validateurs alignés sur les contraintes des DTO backend.
- Messages d'erreur en français, affichés sous le champ.

## Erreurs et sécurité
- Un intercepteur ajoute le JWT, un autre traduit les `ProblemDetail` en notifications.
- Les guards masquent les routes, mais la sécurité réelle est côté backend.

## Interdits
`any`, abonnements manuels non libérés (préférer `toSignal` ou `takeUntilDestroyed`), logique métier dans les templates.
