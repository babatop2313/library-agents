# Écran `catalogue-list` — Catalogue

> Statut : **validé** (2026-11-23, avec remarques intégrées : voir « Décisions de validation » en fin de fiche).
> Maquette : `../mockups/catalogue-list.html`.
> Sources : `docs/specs/catalogue.md` (RM-CAT-27 à 33, 41), `docs/specs/catalogue-plan.md` (§5, décision 6),
> `docs/api/catalogue.md` (GET `/api/v1/books`). Design system : `../design-system.md` (validé).

## 1. But
Permettre à tout utilisateur connecté (`MEMBER`, `LIBRARIAN`) de parcourir et rechercher les livres disponibles à
l'emprunt ; le bibliothécaire peut en plus ouvrir l'ajout d'un livre.

## 2. Accès
- Route `/catalogue` (lazy, sous `authGuard`) ; non connecté → `/login?returnUrl=/catalogue`.
- Point d'entrée : lien **« Catalogue »** dans l'en-tête, à droite du titre « Bibliothèque », visible seulement si un
  utilisateur est connecté ; état actif (`routerLinkActive`, `aria-current="page"`).
- Paramètres d'URL : `?q=<texte>&page=<n base 1>&size=<10|20|50>` (valeurs par défaut omises).

## 3. Mise en page (mobile d'abord)
```
┌ En-tête (existant + lien) ─────────────────────────────────────────────┐
│ [livre] Bibliothèque   Catalogue(actif)          e-mail [rôle] [Se déconnecter] │
└────────────────────────────────────────────────────────────────────────┘
  Zone titre      : h1 « Catalogue »                       [+ Ajouter un livre]  (LIBRARIAN)
                    sous-titre « Livres disponibles à l'emprunt »
  Zone recherche  : [loupe | Rechercher par titre ou auteur ……… | x]
                    compteur « 25 livres disponibles » / « 2 résultats pour "camus" »
  Zone contenu    : p-table (Titre | Auteur | ISBN | Exemplaires disponibles)
                    ou état (chargement, vide, sans résultat, erreur)
  Zone pagination : p-paginator (masqué si 0 résultat)
```
- Conteneur `container-xl px-3 px-md-4`, marges verticales `py-4`. Fond de page `--p-surface-0`.
- **Zone titre** : `d-flex align-items-start justify-content-between gap-3`. Le bouton reste à droite du titre à toutes
  les largeurs (« Catalogue » + bouton tiennent dans 358 px utiles). Absent pour un `MEMBER` (aucun espace réservé).
- **Zone recherche** : champ pleine largeur sur mobile, largeur max 28rem (448 px) à partir de `md`. Libellé visible
  au-dessus du champ. Compteur sous le champ, petit texte secondaire.
- **Tableau** : contour 1 px `--p-surface-200`, rayon 6 px. Colonnes :
  | Colonne | `md` et plus | Mobile (< 768 px) |
  |---|---|---|
  | Titre | texte, graisse 500 | titre (graisse 500) **+ sous-lignes** « Albert Camus » puis « ISBN 9782070360024 » (petit texte secondaire), visibles seulement sous `md` (`d-md-none`) |
  | Auteur | texte | masquée (`d-none d-md-table-cell`) |
  | ISBN | chiffres tabulaires | masquée (`d-none d-md-table-cell`) |
  | Exemplaires disponibles | « 2 / 3 », aligné à droite, largeur fixe 11rem | en-tête raccourci « Disponibles », « 2 / 3 » aligné à droite |
  La sémantique de tableau est conservée (pas d'affichage empilé en `display:block`) : aucun défilement horizontal de la
  page à 320 px. Les titres longs passent à la ligne (`overflow-wrap: anywhere`).
- **Paginateur** : sous le tableau, centré. Desktop : premier, précédent, numéros (5), suivant, dernier, rapport
  « 1 à 10 sur 25 », choix de taille. Mobile : précédent, numéros (3), suivant, choix de taille ; rapport masqué.

## 4. Composants
| Zone | Composant |
|---|---|
| En-tête — lien | `<a routerLink="/catalogue" routerLinkActive="active" ariaCurrentWhenActive="page">` stylé (pas de composant PrimeNG : lien de navigation simple) |
| Titre | `h1` |
| Ajouter | `p-button label="Ajouter un livre" icon="pi pi-plus"` (principal) |
| Recherche | `<label for="catalogue-search">` + `p-iconfield` (`p-inputicon` `pi pi-search` à gauche) + `input pInputText type="search" maxlength="100"` + `p-button` texte `icon="pi pi-times"` (effacer, visible seulement si le champ n'est pas vide) |
| Compteur | `<p>` avec `aria-live="polite"` |
| Tableau | `p-table [lazy]="true"` (sans tri), `caption` visuellement masquée « Livres disponibles » |
| Premier chargement | lignes `p-skeleton` (autant que `size`, 10 au plus) dans le corps du tableau |
| Rechargement | `[loading]="true"` de `p-table` (voile + `pi pi-spinner`), lignes précédentes visibles dessous |
| État vide / sans résultat / erreur | modèle `emptymessage` de `p-table` (une cellule `colspan`) : icône PrimeIcons de 28 px dans une pastille ronde de 64 px (`--p-primary-100` / `--p-primary-700` ; erreur : `--p-red-50` / `--p-red-600`), titre, texte, `p-button` secondaire |
| Pagination | `p-paginator` (`[first]`, `[rows]`, `[totalRecords]`, `[rowsPerPageOptions]="[10, 20, 50]"`, `currentPageReportTemplate="{first} à {last} sur {totalRecords}"`) |
| Notifications | `p-toast` racine existant |

## 5. Données (`docs/api/catalogue.md`)
Appel : `GET /api/v1/books?page=<page URL − 1>&size=<size>&q=<q>` (`q` omis si vide). Réponse `BookPage`.
| Affiché | Champ DTO | Format |
|---|---|---|
| Titre | `Book.title` | texte brut (interpolation échappée) |
| Auteur | `Book.author` | texte brut |
| ISBN | `Book.isbn` | 13 chiffres tels que renvoyés (forme normalisée, sans tirets) |
| Exemplaires disponibles | `Book.availableCopies`, `Book.totalCopies` | visible « 2 / 3 » (`aria-hidden="true"`) + texte masqué « 2 disponibles sur 3 » ; singulier « 1 disponible sur 1 » |
| Compteur | `BookPage.totalElements` | « 25 livres disponibles », « 1 livre disponible » ; avec recherche « 2 résultats pour "camus" », « 1 résultat pour "camus" » |
| Paginateur | `totalElements`, `page`, `size` | `first = page × size` |
- Tri : fixe côté serveur (titre puis ISBN), aucun en-tête cliquable, aucune icône de tri.
- Recherche : anti-rebond 300 ms ou Entrée ; nouvelle recherche → page 1 ; texte rogné, 100 caractères max.
- Taille changée → page 1. Page d'URL au-delà de la dernière → dernière page (`replaceUrl`). `size` d'URL hors
  {10, 20, 50} → 10.
- Rôle : bouton « Ajouter un livre » si `user().roles` contient `LIBRARIAN` (aussi en cumul avec `MEMBER`).

## 6. États
| État | Condition | Rendu |
|---|---|---|
| Chargement initial | premier appel en cours | squelettes dans le tableau (10 lignes max), en-têtes visibles, compteur remplacé par « Chargement du catalogue… » (lu par `aria-live`), paginateur masqué ; `aria-busy="true"` sur le tableau |
| Rechargement | changement de page, taille, recherche, « Réessayer » | voile `[loading]` sur les lignes existantes ; recherche et paginateur restent utilisables (la requête précédente est annulée) |
| Liste | `totalElements > 0` | tableau + paginateur |
| Catalogue vide | `totalElements = 0` et `q` vide | icône `pi pi-book`, titre « Aucun livre disponible pour le moment », texte LIBRARIAN : « Utilisez « Ajouter un livre » pour enrichir le catalogue. » ; MEMBER : « Revenez un peu plus tard : de nouveaux livres sont ajoutés régulièrement. » ; pas de paginateur, compteur masqué |
| Recherche sans résultat | `totalElements = 0` et `q` non vide | icône `pi pi-search`, titre « Aucun livre ne correspond à "camus" » (texte échappé), aide « Vérifiez l'orthographe et les accents, ou essayez une partie du titre ou du nom de l'auteur. », bouton secondaire « Effacer la recherche » ; pas de paginateur |
| Erreur de chargement | échec réseau ou 5xx | toast de l'`errorInterceptor` (« Erreur — Une erreur technique est survenue. Réessayez plus tard. ») **et** dans le tableau : icône `pi pi-exclamation-triangle` (pastille `--p-red-50`, icône `--p-red-600`), titre « Le catalogue n'a pas pu être chargé », texte « Vérifiez votre connexion, puis réessayez. », bouton secondaire `outlined` « Réessayer » (`pi pi-refresh`) ; paginateur et compteur masqués ; la recherche reste modifiable |
| Accès refusé | 403 sur le GET | toast « Accès refusé » ; dans le tableau : titre « Vous n'avez pas les droits nécessaires pour consulter le catalogue », sans bouton « Réessayer » |
| Session expirée | 401 | `authInterceptor` : toast « Session expirée », retour à la connexion avec `returnUrl` (rien à concevoir ici) |
| Succès d'un ajout | 201 depuis le dialogue | toast succès « Livre enregistré » (voir `catalogue-register-dialog`), liste rechargée sur la même page et la même recherche |

## 7. Interactions
- Saisie dans la recherche : appel après 300 ms sans frappe ; **Entrée** déclenche immédiatement ; le bouton **x**
  (« Effacer la recherche ») vide le champ, relance sans filtre, et rend le focus au champ. **Échap** dans le champ
  (comportement natif de `type="search"`) vide aussi le champ.
- « Effacer la recherche » de l'état sans résultat : même effet, focus sur le champ de recherche.
- Paginateur : change `page`/`size` dans l'URL ; après un changement de page, le focus reste sur le contrôle activé
  (le paginateur n'est pas reconstruit) et la page ne défile pas.
- « Réessayer » : relance le même appel (mêmes `page`, `size`, `q`) ; pendant l'appel, le bouton passe en `[loading]`.
- « Ajouter un livre » : ouvre `catalogue-register-dialog` ; à la fermeture, le focus revient sur ce bouton.
- Retour arrière du navigateur : restitue recherche, page et taille (le champ se resynchronise).
- Aucune ligne cliquable (pas de page de détail).

## 8. Accessibilité
- Ordre de tabulation : en-tête (titre, Catalogue, Se déconnecter) → Ajouter un livre → champ de recherche → effacer →
  (Réessayer / Effacer la recherche si état) → paginateur (numéros, puis taille).
- `h1` unique « Catalogue » ; titre du document « Catalogue » (route `title`).
- Tableau : `caption` « Livres disponibles » (classe visuellement masquée) ; en-têtes `<th scope="col">`.
- Cellule exemplaires : « 2 / 3 » `aria-hidden="true"` + `<span class="visually-hidden">2 disponibles sur 3</span>`.
- Compteur `aria-live="polite"` : annonce le nombre de résultats après chaque recherche (« 2 résultats pour "camus" »)
  et « Aucun livre ne correspond à "…" » quand il n'y en a pas.
- Bouton d'effacement : `aria-label="Effacer la recherche"` ; icône seule avec zone cliquable ≥ 40 × 40 px.
- Paginateur : libellés PrimeNG traduits en français (`aria` de `providePrimeNG({ translation })` : « Première page »,
  « Page précédente », « Page suivante », « Dernière page », « Lignes par page ») — **à prévoir** dans la config.
- Focus visible partout (anneau primaire ; blanc sur l'en-tête). Contrastes conformes au design system (AA).
- Le lien actif ne se distingue pas que par la couleur : soulignement épais (3 px) sous le libellé.

## 9. Textes
| Clé | Texte |
|---|---|
| Lien d'en-tête | Catalogue |
| Titre de page / document | Catalogue |
| Sous-titre | Livres disponibles à l'emprunt |
| Bouton principal | Ajouter un livre |
| Libellé de recherche | Rechercher par titre ou auteur |
| Indication dans le champ (placeholder) | Ex. : Camus |
| Bouton d'effacement (aria-label) | Effacer la recherche |
| Compteur sans recherche | {n} livres disponibles / 1 livre disponible |
| Compteur avec recherche | {n} résultats pour "{q}" / 1 résultat pour "{q}" |
| Compteur en chargement | Chargement du catalogue… |
| Légende du tableau (masquée) | Livres disponibles |
| En-têtes | Titre · Auteur · ISBN · Exemplaires disponibles (mobile : Disponibles) |
| Sous-ligne mobile ISBN | ISBN {isbn} |
| Cellule exemplaires (masquée) | {d} disponibles sur {t} / 1 disponible sur {t} |
| Rapport du paginateur | {first} à {last} sur {totalRecords} |
| Vide — titre | Aucun livre disponible pour le moment |
| Vide — texte LIBRARIAN | Utilisez « Ajouter un livre » pour enrichir le catalogue. |
| Vide — texte MEMBER | Revenez un peu plus tard : de nouveaux livres sont ajoutés régulièrement. |
| Sans résultat — titre | Aucun livre ne correspond à "{q}" |
| Sans résultat — aide | Vérifiez l'orthographe et les accents, ou essayez une partie du titre ou du nom de l'auteur. |
| Sans résultat — bouton | Effacer la recherche |
| Erreur — titre | Le catalogue n'a pas pu être chargé |
| Erreur — texte | Vérifiez votre connexion, puis réessayez. |
| Erreur — bouton | Réessayer |
| 403 — titre | Vous n'avez pas les droits nécessaires pour consulter le catalogue |
| Toasts (existants, `errorInterceptor`) | Erreur — Une erreur technique est survenue. Réessayez plus tard. · Accès refusé — Vous n'avez pas les droits nécessaires |

## Ajustements hors écran (validés)
- **En-tête sur mobile** (< 576 px) : l'existant (titre, e-mail, rôles, « Se déconnecter ») plus le lien déborde à
  390 px. Retenu : masquer l'e-mail et les `p-tag` de rôle sous `sm` (`d-none d-sm-inline-flex`), bouton « Se
  déconnecter » réduit à l'icône `pi pi-sign-out` (`aria-label="Se déconnecter"`), marges `px-3 px-md-4` alignées sur la page.
- **En-tête** : fond en dégradé violet → fuchsia (`var(--app-header-bg)`), texte blanc ; lien actif souligné de 3 px blanc.

## Décisions de validation (2026-11-23)
Design validé par l'humain avec deux remarques, reportées ici avant l'implémentation :
1. **Icônes** : aucun emoji ni caractère typographique en guise d'icône ; uniquement des icônes vectorielles modernes,
   c'est-à-dire **PrimeIcons** (`pi pi-*`) dans le code. Correspondance complète dans `../design-system.md` (§4, Icônes) :
   `pi-book`, `pi-search`, `pi-times`, `pi-plus`, `pi-sign-out`, `pi-refresh`, `pi-exclamation-triangle`,
   `pi-spinner`, icônes natives de `p-paginator`. La maquette les dessine en SVG intégré.
2. **Couleurs principales plus modernes et plus ludiques** : palette primaire **violet** d'Aura (couleur d'action
   `{primary.600}` `#7c3aed`, survol `#6d28d9`), en-tête en **dégradé violet → fuchsia** (`#7c3aed` → `#c026d3`),
   pastilles colorées dans les états vides. Tous les contrastes restent AA (détail dans le design system).
Les autres propositions de la fiche (compteur `aria-live`, sous-titre, textes de l'état vide selon le rôle, variante
403, indication « Ex. : Camus », traduction française du paginateur, anneau de focus de 2 px) sont retenues telles quelles.
