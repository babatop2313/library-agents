# Écran `loan-my-loans` — Mes emprunts

> Statut : **validé** (2026-11-26), toutes les recommandations retenues : voir « Décisions (2026-11-26) » en fin de fiche.
> Maquette : `../mockups/loan-my-loans.html`.
> Sources : `docs/specs/loan.md` (RM-LOAN-14, RM-LOAN-21 à 27, RM-LOAN-32), `docs/specs/loan-plan.md` (§6.1, §6.2),
> `docs/api/loan.md` (GET `/api/v1/loans`, POST `/api/v1/loans/{id}/return`). Design system : `../design-system.md`
> (validé, complété le 2026-11-26 pour cet écran).
> Écran voisin imité : `catalogue-list` (mêmes en-tête, zone titre, tableau, états dans le tableau, pastilles).
> Le plan nomme cet écran `loans-list` et la feature Angular `features/loans` : l'identifiant de design retenu ici est
> `loan-my-loans` (feature `loan`) ; le dossier Angular reste celui du plan.

## 1. But
Permettre à un membre (`MEMBER`, y compris en cumul avec `LIBRARIAN`) de voir ses emprunts en cours (au plus 3), de
repérer ceux qui sont en retard et de rendre un livre.

## 2. Accès
- Route `/emprunts` (lazy, sous `authGuard` + `roleGuard('MEMBER')`) ; titre du document « Mes emprunts ».
  Non connecté → `/login?returnUrl=/emprunts`. `LIBRARIAN` seul → redirigé vers `/catalogue` (RM-LOAN-23).
- Point d'entrée : lien **« Mes emprunts »** dans l'en-tête, à droite de « Catalogue », visible seulement si
  `user().roles` contient `MEMBER` ; état actif identique à « Catalogue » (`routerLinkActive="active"`,
  `ariaCurrentWhenActive="page"`, soulignement blanc de 3 px).
- Le toast de succès d'un emprunt (catalogue) indique la date de retour, **sans lien** vers cet écran (décision K).

### En-tête sur mobile (< 576 px)
Avec deux liens, l'en-tête ne tient plus dans 390 px (« Bibliothèque » + « Catalogue » + « Mes emprunts » + bouton
icône ≈ 440 px). Sous `sm`, le libellé « Bibliothèque » est masqué (`d-none d-sm-inline`), seule l'icône `pi pi-book`
reste, avec `aria-label="Bibliothèque, accueil"` sur le lien ; écart entre les liens 16 px (`gap-3`). Total ≈ 330 px :
tout tient sans retour à la ligne. Rien ne change à partir de `sm` (décision H).

## 3. Mise en page (mobile d'abord)
```
┌ En-tête ──────────────────────────────────────────────────────────────────────────┐
│ [livre] Bibliothèque   Catalogue   Mes emprunts(actif)      e-mail [Membre] [Se déconnecter] │
└───────────────────────────────────────────────────────────────────────────────────┘
  Zone titre    : h1 « Mes emprunts »
                  compteur « 2 / 3 emprunts en cours »                (aria-live)
                  aide « Limite atteinte : … »                         (à 3 / 3 seulement)
  Bandeau       : p-message warn « Vous ne pouvez plus emprunter… »   (si au moins un retard)
  Zone contenu  : p-table (Titre | Auteur | ISBN | Emprunté le | À rendre au plus tard le | Statut | [Rendre])
                  ou état (chargement, vide, erreur) dans le tableau
  Dialogue      : p-confirmdialog « Rendre ce livre ? »                (à la demande)
```
- Conteneur `container-xl px-3 px-md-4 py-4`, fond `--p-surface-0` (identique au catalogue).
- **Zone titre** : `h1` puis compteur en petit texte secondaire (14 px, `--p-text-muted-color`). À 3 / 3, une aide
  sur la ligne suivante, même style : « Limite atteinte : rendez un livre pour en emprunter un autre. » (décision J).
  Marge basse 24 px (16 px si le bandeau suit). Pas de bouton d'action dans la zone titre : l'écran n'a pas d'action
  principale permanente (« Rendre » est une action de ligne ; l'action principale de l'état vide est « Parcourir le
  catalogue »).
- **Bandeau** : pleine largeur du contenu, au-dessus du tableau, marge basse 16 px.
- **Tableau** : contour 1 px `--p-surface-200`, rayon 6 px, pas de paginateur (3 lignes au plus). Colonnes selon la
  largeur (point de rupture intermédiaire, décision E) :

  | Colonne | `lg` et plus (≥ 992 px) | `md` à `lg` (768–991 px) | Mobile (< 768 px) |
  |---|---|---|---|
  | Titre | titre, graisse 500 | titre + sous-lignes auteur, « ISBN … », « Emprunté le … » | titre + `p-tag` de statut + sous-lignes auteur, « ISBN … », « Emprunté le … », « À rendre au plus tard le … » |
  | Auteur | texte | masquée | masquée |
  | ISBN | chiffres tabulaires, sans retour à la ligne | masquée | masquée |
  | Emprunté le | date, chiffres tabulaires, largeur 8rem | masquée | masquée |
  | À rendre au plus tard le | date, largeur 9rem (l'en-tête passe sur deux lignes) ; en retard : `--p-red-700`, graisse 600 | idem | masquée (sous-ligne, même style si retard) |
  | Statut | `p-tag`, largeur 8rem | idem | masquée (le tag passe sous le titre) |
  | Action (en-tête masqué « Action ») | bouton « Rendre », aligné à droite, largeur 7rem | idem | idem |

  Classes : Auteur, ISBN et Emprunté le `d-none d-lg-table-cell` ; échéance et statut `d-none d-md-table-cell` ;
  sous-lignes `d-lg-none` (auteur, ISBN, emprunté le) et `d-md-none` (tag, échéance). À 768 px, le titre garde
  ≈ 200 px. Sémantique de tableau conservée, aucun défilement horizontal de la page à 320 px, titres longs à la ligne
  (`overflow-wrap: anywhere`).
- Lignes : alignement vertical en haut (comme le catalogue) ; le bouton « Rendre » est aligné sur la première ligne du titre.

## 4. Composants
| Zone | Composant |
|---|---|
| En-tête — lien | `<a routerLink="/emprunts" routerLinkActive="active" ariaCurrentWhenActive="page" class="app-nav-link">Mes emprunts</a>` (même style que « Catalogue ») |
| Titre | `h1` avec `tabindex="-1"` (cible du focus après un retour réussi) |
| Compteur | `<p>` avec `aria-live="polite"` ; aide de limite dans un second `<p>` (petit texte secondaire) |
| Bandeau de retard | `p-message severity="warn"` (icône `pi pi-exclamation-triangle`), non fermable |
| Tableau | `p-table` (sans tri, sans pagination), `caption` visuellement masquée « Mes emprunts en cours » |
| Statut | `p-tag` : « En cours » `severity="info"` ; « En retard » `severity="danger"` avec `icon="pi pi-exclamation-circle"` |
| Rendre | `p-button label="Rendre" severity="secondary" [outlined]="true" size="small"`, `ariaLabel` explicite, `[loading]` sur la ligne en cours, `[disabled]` sur les autres lignes pendant un retour |
| Premier chargement | 3 lignes `p-skeleton` dans le corps du tableau |
| Rechargement (après un retour, « Réessayer ») | `[loading]="true"` de `p-table` (voile + `pi pi-spinner`) sur les lignes existantes |
| États vide / erreur | modèle `emptymessage` de `p-table` : pastille 64 px + icône 28 px, titre, texte, bouton (identique au catalogue) |
| Vide — action | `p-button` principal « Parcourir le catalogue » (`icon="pi pi-book"`) rendu comme lien (`routerLink="/catalogue"`) |
| Erreur — action | `p-button` secondaire `outlined` « Réessayer » (`icon="pi pi-refresh"`) |
| Confirmation | `p-confirmdialog` (dans la page, `ConfirmationService` fourni par la page), `defaultFocus="reject"` : en-tête, message, « Annuler » (secondaire `text`) et « Rendre » (principal) |
| Notifications | `p-toast` racine existant (`MessageService`), couleurs `warn` / `success` surchargées (design system, §1) |

## 5. Données (`docs/api/loan.md`)
Appel : `GET /api/v1/loans` (aucun paramètre). Réponse `CurrentLoan[]`, déjà triée par `dueOn` croissant puis `id` :
l'écran **ne retrie pas**.

| Affiché | Champ DTO | Format |
|---|---|---|
| Titre | `CurrentLoan.title` | texte brut ; **`null` → « Livre indisponible »** (italique, `--p-text-muted-color`) |
| Auteur | `CurrentLoan.author` | texte brut ; `null` → tiret cadratin visible `aria-hidden="true"` + texte visuellement masqué « Non renseigné » (décision G) ; sous-ligne mobile remplacée par « Ce livre n'est plus au catalogue. » |
| ISBN | `CurrentLoan.isbn` | 13 chiffres tels que renvoyés ; `null` → comme l'auteur ; sous-ligne absente si `null` |
| Emprunté le | `CurrentLoan.borrowedOn` | `jj/mm/aaaa` (`formatIsoDate`, découpage de la chaîne, jamais `new Date()`) |
| À rendre au plus tard le | `CurrentLoan.dueOn` | `jj/mm/aaaa` ; style d'alerte si `overdue` (le jour de l'échéance n'est pas un retard, RM-LOAN-05) |
| Statut | `CurrentLoan.overdue` | `false` → « En cours » ; `true` → « En retard » |
| Compteur | longueur du tableau | « {n} / 3 emprunts en cours » (`MAX_ACTIVE_LOANS = 3`, affichage seulement) ; texte masqué pour les lecteurs d'écran : « {n} emprunts en cours sur 3 autorisés » (singulier : « 1 emprunt en cours sur 3 autorisés », « 0 emprunt en cours sur 3 autorisés ») |
| Aide de limite | longueur = 3 | « Limite atteinte : rendez un livre pour en emprunter un autre. » |
| Bandeau | `some(overdue)` | affiché si au moins un `overdue = true` |

Rendre : `POST /api/v1/loans/{CurrentLoan.id}/return`, sans corps ; 204 attendu.
Aucune pagination, aucune recherche, aucun tri côté client. Le calcul du retard vient du backend (`overdue`) : l'écran
ne compare jamais de dates.

## 6. États
| État | Condition | Rendu |
|---|---|---|
| Chargement initial | premier `GET` en cours | en-têtes visibles, 3 lignes `p-skeleton`, compteur remplacé par « Chargement de vos emprunts… », bandeau et aide absents, `aria-busy="true"` sur le tableau |
| Liste | `length ≥ 1`, aucun retard | compteur, tableau, pas de bandeau |
| Limite atteinte | `length = 3` | aide « Limite atteinte : rendez un livre pour en emprunter un autre. » sous le compteur |
| Liste avec retard | au moins un `overdue` | bandeau d'avertissement au-dessus du tableau ; lignes en retard : tag « En retard » (danger, icône) et date d'échéance en rouge graisse 600 |
| Livre indisponible | `title = null` | titre « Livre indisponible » en italique secondaire, sous-ligne « Ce livre n'est plus au catalogue. » ; auteur et ISBN non renseignés ; dates, statut et « Rendre » normaux |
| Retour en cours | après confirmation | bouton de la ligne en `[loading]` (libellé conservé), autres boutons « Rendre » désactivés ; un seul envoi à la fois |
| Retour réussi | 204 | toast succès « Livre rendu » ; tableau rechargé (voile `[loading]`), la ligne disparaît ; compteur mis à jour (annoncé), aide de limite retirée ; bandeau retiré s'il n'y a plus de retard ; focus sur le `h1` |
| Dernier livre rendu | 204 et liste vide après rechargement | état vide (ci-dessous) |
| Déjà rendu | 409 `LOAN_ALREADY_RETURNED` | toast `warn` « Cet emprunt est déjà rendu », rechargement |
| Introuvable | 404 `LOAN_NOT_FOUND` | toast `warn` « Emprunt introuvable », rechargement |
| Service occupé | 503 `LOAN_BUSY` (retour) | toast `warn` émis par l'écran « Le service est momentanément occupé, réessayez » (pas de toast générique en doublon : `skipErrorToast` couvre aussi 503) ; **pas de rechargement**, la ligne reste en place et les boutons redeviennent actifs (décision D) |
| Vide | `[]` | compteur « 0 / 3 emprunts en cours » ; dans le tableau : pastille `pi pi-book` (`--p-primary-100` / `--p-primary-700`), titre « Vous n'avez aucun emprunt en cours », texte « Choisissez un livre dans le catalogue : vous pouvez en emprunter jusqu'à 3 à la fois, pour 14 jours. », bouton principal « Parcourir le catalogue » |
| Erreur de chargement | réseau ou 5xx sur le `GET` | toast de l'`errorInterceptor` (« Erreur — Une erreur technique est survenue. Réessayez plus tard. ») **et** dans le tableau : pastille `pi pi-exclamation-triangle` (`--p-red-50` / `--p-red-600`), titre « Vos emprunts n'ont pas pu être chargés », texte « Vérifiez votre connexion, puis réessayez. », bouton « Réessayer » (`[loading]` pendant l'appel) ; compteur, aide et bandeau masqués |
| Accès refusé | 403 sur le `GET` (rôle retiré entre-temps) | toast « Accès refusé » de l'intercepteur ; dans le tableau : titre « Vous n'avez pas les droits nécessaires pour consulter vos emprunts », sans bouton (même variante que le catalogue) |
| Session expirée | 401 | `authInterceptor` existant : toast « Session expirée », connexion avec `returnUrl=/emprunts` |

## 7. Interactions
- **Rendre** : ouvre `p-confirmdialog` (modal, `draggable=false`, Échap et le bouton de fermeture annulent) :
  - en-tête « Rendre ce livre ? » ;
  - message « Confirmez-vous le retour de « {titre} » ? » ; si `title = null` : « Confirmez-vous le retour de ce
    livre, emprunté le {jj/mm/aaaa} ? » ;
  - boutons « Annuler » (secondaire `text`, à gauche) et « Rendre » (principal, à droite) ;
  - focus initial sur « Annuler » (`defaultFocus="reject"`, décision F) : un double appui sur Entrée ne rend pas le
    livre par mégarde.
- **Annuler** : aucune requête ; le focus revient sur le bouton « Rendre » de la ligne.
- **Confirmer** : un seul `POST` (signal `returningId`) ; voir les états ci-dessus pour chaque réponse.
- **Réessayer** : relance le `GET`.
- **Parcourir le catalogue** : navigation vers `/catalogue`.
- Aucune ligne cliquable (pas de page de détail) ; pas de raccourci clavier propre à l'écran.

## 8. Accessibilité
- Ordre de tabulation : en-tête (titre, Catalogue, Mes emprunts, Se déconnecter) → boutons « Rendre » dans l'ordre des
  lignes → (Réessayer / Parcourir le catalogue si état). Dans le dialogue : piège de focus (natif PrimeNG), Annuler →
  Rendre → fermer.
- `h1` unique « Mes emprunts » (`tabindex="-1"`, anneau de focus seulement en `:focus-visible`).
- Tableau : `caption` « Mes emprunts en cours » (visuellement masquée), `<th scope="col">` ; en-tête de la colonne
  d'action « Action » visuellement masqué.
- Libellés explicites (RM-LOAN-26) : `ariaLabel` « Rendre {titre} » ; si `title = null` : « Rendre le livre
  indisponible emprunté le {jj/mm/aaaa} ».
- Statut jamais porté par la seule couleur : le texte du tag (« En cours », « En retard ») porte l'information, l'icône
  `pi pi-exclamation-circle` (décorative) la renforce ; la date en rouge est un complément.
- Valeur absente : tiret cadratin `aria-hidden` (valeur vide, pas une icône) + « Non renseigné » masqué.
- Compteur `aria-live="polite"` : annonce le nouveau nombre après un retour. Le texte visible « 2 / 3 » est
  `aria-hidden`, doublé du texte masqué « 2 emprunts en cours sur 3 autorisés ». L'aide de limite est dans la même
  région `aria-live` (annoncée quand elle apparaît ou disparaît).
- Bandeau : `p-message` sans rôle d'alerte (il est présent dès l'affichage, juste après le titre, et lu dans l'ordre) ;
  icône décorative.
- Contrastes (AA, détail dans le design system) : tag info ≈ 5,9:1 ; tag danger ≈ 5,9:1 ; date en retard
  `#b91c1c` sur blanc ≈ 6,5:1 ; « Livre indisponible » `#64748b` sur blanc ≈ 4,8:1 ; bandeau et toasts `warn`
  `{yellow.800}` ≈ 7:1 ; toasts `success` `{green.800}` ≈ 7:1 (surcharges du preset, décision A).
- Focus visible partout (anneau primaire 2 px ; blanc dans l'en-tête). Zones cliquables ≥ 32 px de haut (bouton `small`).

## 9. Textes
| Clé | Texte |
|---|---|
| Lien d'en-tête | Mes emprunts |
| Lien du titre de l'application sous 576 px (aria-label) | Bibliothèque, accueil |
| Titre de page / document | Mes emprunts |
| Compteur (visible) | {n} / 3 emprunts en cours |
| Compteur (masqué) | {n} emprunts en cours sur 3 autorisés · 1 emprunt en cours sur 3 autorisés · 0 emprunt en cours sur 3 autorisés |
| Aide à 3 / 3 | Limite atteinte : rendez un livre pour en emprunter un autre. |
| Compteur en chargement | Chargement de vos emprunts… |
| Bandeau de retard | Vous ne pouvez plus emprunter tant que vous avez un livre en retard |
| Légende du tableau (masquée) | Mes emprunts en cours |
| En-têtes | Titre · Auteur · ISBN · Emprunté le · À rendre au plus tard le · Statut · Action (masqué) |
| Sous-lignes | ISBN {isbn} · Emprunté le {date} · À rendre au plus tard le {date} |
| Tags | En cours · En retard |
| Livre introuvable — titre | Livre indisponible |
| Livre introuvable — sous-ligne | Ce livre n'est plus au catalogue. |
| Valeur absente (masquée) | Non renseigné |
| Bouton de ligne | Rendre |
| Bouton de ligne (aria-label) | Rendre {titre} · Rendre le livre indisponible emprunté le {date} |
| Dialogue — en-tête | Rendre ce livre ? |
| Dialogue — message | Confirmez-vous le retour de « {titre} » ? · Confirmez-vous le retour de ce livre, emprunté le {date} ? |
| Dialogue — boutons | Annuler · Rendre |
| Toast succès | Livre rendu |
| Toast 409 | Cet emprunt est déjà rendu |
| Toast 404 | Emprunt introuvable |
| Toast 503 | Le service est momentanément occupé, réessayez |
| Vide — titre | Vous n'avez aucun emprunt en cours |
| Vide — texte | Choisissez un livre dans le catalogue : vous pouvez en emprunter jusqu'à 3 à la fois, pour 14 jours. |
| Vide — bouton | Parcourir le catalogue |
| Erreur — titre | Vos emprunts n'ont pas pu être chargés |
| Erreur — texte | Vérifiez votre connexion, puis réessayez. |
| Erreur — bouton | Réessayer |
| 403 — titre | Vous n'avez pas les droits nécessaires pour consulter vos emprunts |
| Toasts existants (`errorInterceptor`) | Erreur — Une erreur technique est survenue. Réessayez plus tard. · Accès refusé — Vous n'avez pas les droits nécessaires |

## Évolution liée : colonne « Emprunter » du catalogue
Décrite dans `catalogue-list.md`, section « Évolution loan » (bouton par ligne, chargement, désactivation, toasts de
RM-LOAN-22, 503, mobile). Aucune nouvelle maquette du catalogue.

## Décisions (2026-11-26)
Design validé par l'humain ; toutes les recommandations du designer sont retenues sauf K.
- **A.** Preset Aura surchargé : texte `warn` `{yellow.800}` `#854d0e` (≈ 7:1 au lieu de ≈ 2,9:1) et `success`
  `{green.800}` `#166534` (≈ 7:1 au lieu de ≈ 3:1), pour `p-message` et `p-toast`. Documenté dans
  `../design-system.md` (§1) ; s'applique aussi aux toasts existants « Livre enregistré » et « Session expirée ».
- **B.** Design system complété : badges d'état `p-tag` `info` / `danger`, jeton `--p-red-700`, icône
  `pi pi-exclamation-circle`, bandeau `p-message warn`, règles du `p-confirmdialog`.
- **C.** Libellé « À rendre au plus tard le » (colonne, sous-ligne) au lieu de « À rendre avant », le jour de
  l'échéance n'étant pas un retard ; toast d'emprunt « Livre emprunté », détail « À rendre au plus tard le {date}. ».
  La spec RM-LOAN-22 / 24 dit « à rendre avant » : le libellé validé ici fait foi pour l'interface.
- **D.** 503 `LOAN_BUSY` au retour : toast `warn` « Le service est momentanément occupé, réessayez », sans
  rechargement ; idem à l'emprunt depuis le catalogue.
- **E.** Point de rupture intermédiaire 768–991 px (auteur, ISBN et date d'emprunt en sous-lignes).
- **F.** Focus initial du dialogue de confirmation sur « Annuler ».
- **G.** Valeur absente : tiret cadratin `aria-hidden` + texte masqué « Non renseigné ».
- **H.** En-tête sous 576 px : icône seule, `aria-label="Bibliothèque, accueil"`.
- **I.** Catalogue sous 768 px (avec la colonne « Emprunter ») : colonne « Disponibles » masquée, « {n} disponibles
  sur {m} » en sous-ligne du titre (voir `catalogue-list.md`).
- **J.** À 3 / 3 : aide « Limite atteinte : rendez un livre pour en emprunter un autre. » sous le compteur (dessinée).
- **K.** Refusé : pas de lien vers « Mes emprunts » dans le toast d'emprunt.
