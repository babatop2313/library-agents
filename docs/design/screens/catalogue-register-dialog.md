# Écran `catalogue-register-dialog` — Ajouter un livre

> Statut : **validé** (2026-11-23, avec remarques intégrées : voir « Décisions de validation » en fin de fiche).
> Maquette : `../mockups/catalogue-register-dialog.html`.
> Sources : `docs/specs/catalogue.md` (RM-CAT-33 à 41), `docs/specs/catalogue-plan.md` (§5.2 `book-form-dialog`,
> décision 6), `docs/api/catalogue.md` (POST `/api/v1/books`). Design system : `../design-system.md` (validé).

## 1. But
Permettre au bibliothécaire (`LIBRARIAN`) d'enregistrer un nouveau livre au catalogue sans quitter la liste.

## 2. Accès
- Boîte de dialogue modale ouverte par le bouton « Ajouter un livre » de `catalogue-list` (`/catalogue`) ; pas de route
  propre. Bouton affiché seulement si l'utilisateur a le rôle `LIBRARIAN` (le backend reste la référence : 403).
- Le formulaire est **réinitialisé à chaque ouverture** (champs vides, aucune erreur).

## 3. Mise en page
```
┌ Ajouter un livre ─────────────────────────────── [x] ┐
│ Tous les champs sont obligatoires.                    │
│ [p-message erreur générale]          (si besoin)      │
│ ISBN                                                  │
│ [ 978-2-07-036822-8                       ]           │
│ 13 chiffres ; tirets et espaces acceptés.             │
│ (x) message d'erreur                                  │
│ Titre                                                 │
│ [                                          ]          │
│ Auteur                                                │
│ [                                          ]          │
│ Nombre d'exemplaires                                  │
│ [ 3              ][-][+]                              │
│ Entre 1 et 1000.                                      │
├───────────────────────────────────────────────────────┤
│                              [Annuler] [✓ Enregistrer] │
└───────────────────────────────────────────────────────┘
```
- `p-dialog` modal, largeur `32rem` (512 px) ; sous 576 px : `calc(100vw - 32px)` (`[breakpoints]="{ '575px': 'calc(100vw - 32px)' }"`), soit 16 px de marge de chaque côté ; hauteur max 90vh, le contenu défile à l'intérieur, le pied reste visible.
- Champs empilés, pleine largeur, espacement 20 px entre groupes (libellé → champ 6 px, champ → aide 4 px).
- Champ « Nombre d'exemplaires » : largeur 12rem à partir de `sm`, pleine largeur en dessous.
- Pied : boutons alignés à droite, « Annuler » puis « Enregistrer », espacement 8 px ; sous 576 px, les deux boutons se
  partagent la largeur (50 % chacun), « Enregistrer » à droite.

## 4. Composants
| Élément | Composant |
|---|---|
| Conteneur | `p-dialog header="Ajouter un livre" [modal]="true" [draggable]="false" [resizable]="false" [closable]="true" [closeOnEscape]="true" [dismissableMask]="false"` |
| Formulaire | `<form novalidate>` (formulaire réactif typé), `aria-busy` pendant l'envoi |
| Mention obligatoire | `<p>` petit texte secondaire |
| Erreur générale | `p-message severity="error"` (avec icône), `tabindex="-1"` pour recevoir le focus |
| ISBN, Titre, Auteur | `<label for>` + `input pInputText` (`w-100`), classe `ng-invalid ng-dirty` pour la bordure rouge Aura |
| Nombre d'exemplaires | `<label for>` + `p-inputnumber inputId="book-copies" [min]="1" [max]="1000" [useGrouping]="false" [showButtons]="true" buttonLayout="horizontal"` (boutons − / + avec `pi pi-minus` / `pi pi-plus`) |
| Aide de saisie | `<small id="…-help">` texte secondaire |
| Erreur de champ | `p-message severity="error" variant="simple" size="small" id="…-error"` (icône `pi pi-times-circle`) |
| Annuler | `p-button label="Annuler" severity="secondary" [text]="true"` |
| Enregistrer | `p-button type="submit" label="Enregistrer" icon="pi pi-check" [loading]="submitting()"` |

## 5. Données (`docs/api/catalogue.md`)
Envoi : `POST /api/v1/books`, corps `RegisterBookRequest`, avec `skipErrorToast(400, 409)`.
| Champ | Contrôle | DTO | Validation client (jamais plus stricte que le backend) |
|---|---|---|---|
| ISBN | `isbn` (texte) | `isbn` | obligatoire ; après retrait des espaces et tirets, 13 chiffres. Envoyé **tel que saisi** (le backend normalise) |
| Titre | `title` | `title` | obligatoire, non blanc, ≤ 255 caractères après trim ; `maxlength` non posé (le client n'empêche pas la saisie, il signale) |
| Auteur | `author` | `author` | idem titre |
| Nombre d'exemplaires | `copies` (`p-inputnumber`) | `copies` | obligatoire, entier, 1 à 1000 ; **vide à l'ouverture** (pas de valeur par défaut) |
Réponse 201 `Book` : `title` sert au toast de confirmation.

## 6. États
| État | Rendu |
|---|---|
| Ouverture | champs vides, aucune erreur, focus dans « ISBN » |
| Saisie | erreurs client affichées **à la sortie du champ** (champ touché) ou à la soumission ; une erreur disparaît dès que la saisie devient valide |
| Soumission invalide (client) | aucun appel réseau ; toutes les erreurs affichées ; focus sur le premier champ en erreur (ordre ISBN, Titre, Auteur, Exemplaires) |
| Envoi en cours | « Enregistrer » en `[loading]` (icône `pi pi-spinner`, libellé conservé) et désactivé ; champs en lecture seule (`readonly`, pas `disabled` pour garder les valeurs lisibles) ; « Annuler », la croix et Échap **inactifs** ; `aria-busy="true"` ; un seul envoi (double clic = une requête) |
| 400 avec `errors[]` connus | message du backend sous chaque champ concerné (`field` = `isbn`, `title`, `author`, `copies`), champ marqué invalide, valeurs conservées, focus sur le premier champ en erreur ; l'erreur serveur d'un champ disparaît à sa première modification |
| 400 avec `field` inconnu, `errors: []` ou sans `errors` | `p-message` général en tête : « Le livre n'a pas pu être enregistré. » + les messages du backend non rattachés (un par ligne) ou, s'il n'y en a pas, « La demande n'a pas pu être traitée. Vérifiez les champs, puis réessayez. » ; focus sur ce message |
| 409 `ISBN_ALREADY_EXISTS` | sous l'ISBN : `detail` du backend s'il existe (« Un livre avec cet ISBN existe déjà »), sinon « Cet ISBN existe déjà au catalogue » ; focus sur l'ISBN ; aucun ajout à la liste |
| 403 | toast `errorInterceptor` « Accès refusé — Vous n'avez pas les droits nécessaires » ; dialogue ouvert, valeurs conservées, focus rendu à « Enregistrer » |
| 5xx / réseau | toast « Erreur — Une erreur technique est survenue. Réessayez plus tard. » ; dialogue ouvert, valeurs conservées, focus rendu à « Enregistrer » |
| 401 | `authInterceptor` : toast « Session expirée », retour à la connexion (le dialogue disparaît avec la page) |
| Succès 201 | dialogue fermé, formulaire réinitialisé, toast `success` « Livre enregistré » / « « L'Étranger » a été ajouté au catalogue. » (3 s), liste rechargée (même page, même recherche), focus rendu au bouton « Ajouter un livre » |

## 7. Interactions
- **Entrée** dans un champ texte soumet le formulaire (bouton `type="submit"`).
- « Enregistrer » est **toujours cliquable** hors envoi (pas désactivé quand le formulaire est invalide) : le clic
  révèle les erreurs et place le focus.
- « Annuler », la croix ou **Échap** : ferment sans confirmation, même si des champs sont remplis (formulaire court ;
  il sera vidé à la prochaine ouverture). Clic sur le masque : sans effet.
- `p-inputnumber` : flèches haut/bas du clavier et boutons − / + changent la valeur de 1, bornée à 1 et 1000.

## 8. Accessibilité
- `p-dialog` : `role="dialog"`, `aria-modal="true"`, `aria-labelledby` sur le titre ; focus piégé dans le dialogue ;
  focus initial sur « ISBN » (`pAutoFocus`) ; au retour, focus sur « Ajouter un livre ». Croix : `aria-label="Fermer"`.
- Chaque champ : `<label for>` visible ; `aria-describedby` = id de l'aide **et**, s'il y a lieu, id de l'erreur
  (ex. `book-isbn-help book-isbn-error`) ; `aria-invalid="true"` quand le champ est en erreur ; `aria-required="true"`.
- Erreurs de champ rendues dans le DOM à leur apparition (pas seulement colorées) ; le focus déplacé sur le premier
  champ en erreur fait lire son libellé, sa valeur, puis l'erreur (via `aria-describedby`). Pas de `role="alert"` sur
  chaque erreur de champ (évite une rafale d'annonces) ; `role="alert"` sur le message général.
- Bordure rouge **et** icône + texte : l'erreur ne repose pas sur la couleur seule.
- Ordre de tabulation : Fermer (croix, en tête par PrimeNG) → ISBN → Titre → Auteur → Exemplaires (−, champ, +) →
  Annuler → Enregistrer. Les boutons − / + de `p-inputnumber` : `aria-label` « Diminuer » / « Augmenter ».
- Contrastes AA (design system) ; libellés à 16 px ; zones cliquables ≥ 40 px.

## 9. Textes
| Clé | Texte |
|---|---|
| Titre du dialogue | Ajouter un livre |
| Croix (aria-label) | Fermer |
| Mention | Tous les champs sont obligatoires. |
| ISBN — libellé | ISBN |
| ISBN — aide | 13 chiffres ; les tirets et les espaces sont acceptés (ex. : 978-2-07-036822-8). |
| ISBN — requis | Saisissez l'ISBN du livre. |
| ISBN — format | L'ISBN doit comporter 13 chiffres (tirets et espaces acceptés). |
| ISBN — doublon (repli 409) | Cet ISBN existe déjà au catalogue |
| Titre — libellé | Titre |
| Titre — aide | 255 caractères maximum. |
| Titre — requis / blanc | Saisissez le titre du livre. |
| Titre — trop long | Le titre ne doit pas dépasser 255 caractères. |
| Auteur — libellé | Auteur |
| Auteur — aide | Prénom et nom, 255 caractères maximum. |
| Auteur — requis / blanc | Saisissez l'auteur du livre. |
| Auteur — trop long | Le nom de l'auteur ne doit pas dépasser 255 caractères. |
| Exemplaires — libellé | Nombre d'exemplaires |
| Exemplaires — aide | Entre 1 et 1000. |
| Exemplaires — requis | Saisissez le nombre d'exemplaires. |
| Exemplaires — bornes | Le nombre d'exemplaires doit être compris entre 1 et 1000. |
| Boutons − / + (aria-label) | Diminuer · Augmenter |
| Erreur générale — titre | Le livre n'a pas pu être enregistré. |
| Erreur générale — repli | La demande n'a pas pu être traitée. Vérifiez les champs, puis réessayez. |
| Annuler | Annuler |
| Enregistrer | Enregistrer |
| Toast succès | Livre enregistré — « {title} » a été ajouté au catalogue. |
| Erreurs serveur par champ | message du backend, affiché tel quel (ex. « Le champ 'isbn' a un format invalide ») |

## Décisions de validation (2026-11-23)
Design validé par l'humain avec deux remarques, reportées ici avant l'implémentation :
1. **Icônes** : aucun emoji ni caractère typographique en guise d'icône ; uniquement **PrimeIcons** (`pi pi-*`) :
   croix de fermeture `pi pi-times` (native de `p-dialog`), « Enregistrer » `pi pi-check`, boutons de
   `p-inputnumber` `pi pi-minus` / `pi pi-plus`, erreurs `pi pi-times-circle` (native de `p-message`), envoi
   `pi pi-spinner` (`[loading]`), toast de succès `pi pi-check-circle` (natif). Correspondance dans
   `../design-system.md` (§4, Icônes).
2. **Couleurs** : palette primaire violette (bouton « Enregistrer » `#7c3aed`, survol `#6d28d9`, anneau de focus
   violet) ; les couleurs d'erreur (rouge) et de succès (vert) d'Aura sont inchangées.
Les autres choix de la fiche sont retenus tels quels : bouton « Enregistrer » toujours cliquable hors envoi, fermeture
bloquée pendant l'envoi et sans confirmation sinon, nombre d'exemplaires vide à l'ouverture, 409 affiché avec le
`detail` du backend puis, à défaut, « Cet ISBN existe déjà au catalogue » (ordre du plan validé).

## Écarts avec l'existant (à traiter plus tard)
- La page de connexion désactive son bouton tant que le formulaire est invalide et affiche les erreurs en
  `<small class="text-danger">` (couleur Bootstrap hors jetons PrimeNG ; sa classe `form-label`, elle, n'est pas fournie
  par les utilitaires chargés). Ici : bouton toujours actif et erreurs en `p-message` simple, conformément au design
  system ; aligner la connexion plus tard.
