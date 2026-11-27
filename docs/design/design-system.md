# Design system — Bibliothèque

> Statut : **validé** le 2026-11-23 avec deux remarques intégrées : palette primaire plus moderne (violet, en-tête en
> dégradé violet → fuchsia) et icônes vectorielles modernes uniquement (aucun emoji ni caractère en guise d'icône).
> Complété et validé le 2026-11-26 (écran « Mes emprunts ») : surcharge des textes `warn` / `success` pour le contraste
> AA, badges d'état `info` / `danger`, jeton `--p-red-700`, icône `pi pi-exclamation-circle`, bandeau `p-message`,
> règles du `p-confirmdialog`.
> Base : PrimeNG 21, preset **Aura** (`@primeuix/themes/aura`), PrimeIcons ; Bootstrap 5 **grille et utilitaires
> seulement** (`bootstrap-grid`, `bootstrap-utilities` : pas de reboot, pas de composants Bootstrap).
> Ce document **précise** le thème Aura, il ne le contredit pas. Les couleurs viennent toutes des palettes d'Aura ;
> les surcharges du preset sont signalées « Surcharge du preset ».

## 1. Couleurs

### Palette (jetons Aura)
| Rôle | Jeton PrimeNG | Valeur | Usage |
|---|---|---|---|
| Primaire (palette) | `--p-primary-50` … `--p-primary-950` | **violet** d'Aura (`{violet.50}` `#f5f3ff` … `{violet.950}` `#2e1065`) à la place de l'émeraude | dérivés, fonds légers (`--p-primary-50`, `--p-primary-100`) |
| Primaire (couleur d'action) | `--p-primary-color` | `{primary.600}` `#7c3aed` | bouton principal, lien actif, anneau de focus, page active du paginateur |
| Primaire survol / actif | `--p-primary-hover-color`, `--p-primary-active-color` | `{primary.700}` `#6d28d9` / `{primary.800}` `#5b21b6` | survol, appui |
| Texte sur primaire | `--p-primary-contrast-color` | `#ffffff` | libellés des boutons principaux, en-tête |
| Accent (touche ludique) | `--p-fuchsia-600` | `#c026d3` | **uniquement** la fin du dégradé de l'en-tête ; jamais pour une action ni un état |
| Fond de l'en-tête | `--app-header-bg` (variable de l'application) | `linear-gradient(90deg, {primary.600}, {fuchsia.600})` | en-tête de l'application |
| Surbrillance | `--p-highlight-background` / `--p-highlight-color` | `{primary.50}` / `{primary.700}` | page courante du paginateur, élément sélectionné |
| Pastille d'état vide | `--p-primary-100` + `--p-primary-700` | `#ede9fe` / `#6d28d9` | cercle de 64 px autour de l'icône des états vides (erreur : `--p-red-50` + `--p-red-600`) |
| Fond de page | `--p-surface-0` | `#ffffff` | fond général |
| Fond secondaire | `--p-surface-50` | `#f8fafc` | en-têtes de tableau, zones d'état (vide, erreur) |
| Bordures | `--p-surface-200` | `#e2e8f0` | contour des tableaux, séparateurs, champs (`--p-form-field-border-color` = `{surface.300}`) |
| Texte principal | `--p-text-color` | `{surface.700}` `#334155` | corps de texte, cellules |
| Texte fort | `--p-surface-900` | `#0f172a` | titres de page |
| Texte secondaire | `--p-text-muted-color` | `{surface.500}` `#64748b` | aides de saisie, sous-lignes, compteurs |
| Erreur (texte) | `--p-red-600` | `#dc2626` | messages d'erreur sous les champs (`p-message` simple) |
| Erreur (bordure) | `--p-form-field-invalid-border-color` | `{red.400}` `#f87171` | champ invalide |
| Succès | `--p-green-*` | toast `success` d'Aura, **texte surchargé** `{green.800}` `#166534` (voir « Surcharge du preset ») | confirmation (« Livre enregistré », « Livre emprunté », « Livre rendu ») |
| Avertissement | `--p-yellow-*` / `orange` | toast et `p-message` `warn` d'Aura, **texte surchargé** `{yellow.800}` `#854d0e` | « Session expirée » (existant), refus métier, bandeau de retard |
| Erreur forte (texte) | `--p-red-700` | `#b91c1c` | date d'échéance dépassée (« Mes emprunts »), texte du tag `danger` |
| Info | `--p-sky-*` | toast `info` d'Aura | non utilisé pour l'instant |

### Contrastes (AA : 4,5:1 texte normal, 3:1 grands textes et composants)
| Paire | Ratio | Verdict |
|---|---|---|
| Blanc sur `#10b981` (émeraude Aura par défaut, abandonnée) | ≈ 2,5:1 | Échec AA |
| Blanc sur `#7c3aed` (`primary.600`, couleur d'action) | ≈ 5,7:1 | AA |
| Blanc sur `#c026d3` (fin du dégradé de l'en-tête) | ≈ 4,7:1 | AA (texte de l'en-tête lisible sur tout le dégradé) |
| Blanc sur `#6d28d9` (survol) | ≈ 7,1:1 | AAA |
| `#6d28d9` sur `#f5f3ff` (page active, surbrillance) | ≈ 6,5:1 | AA |
| `#334155` sur blanc | ≈ 10:1 | AAA |
| `#64748b` sur blanc | ≈ 4,8:1 | AA |
| `#64748b` sur `#f8fafc` | ≈ 4,6:1 | AA (limite : ne pas éclaircir) |
| `#dc2626` sur blanc | ≈ 4,8:1 | AA |
| `#b91c1c` (`red.700`) sur blanc | ≈ 6,5:1 | AA |
| `#ca8a04` (`yellow.600`, `warn` Aura par défaut) sur `#fefce8` | ≈ 2,9:1 | **Échec AA** : remplacé |
| `#854d0e` (`yellow.800`, `warn` surchargé) sur `#fefce8` | ≈ 7:1 | AAA |
| `#16a34a` (`green.600`, `success` Aura par défaut) sur `#f0fdf4` | ≈ 3:1 | **Échec AA** : remplacé |
| `#166534` (`green.800`, `success` surchargé) sur `#f0fdf4` | ≈ 7:1 | AAA |
| `#0369a1` (tag `info`, `sky.700`) sur `#e0f2fe` | ≈ 5,9:1 | AA |
| `#b91c1c` (tag `danger`, `red.700`) sur `#fee2e2` | ≈ 5,9:1 | AA |

**Surcharge du preset** (dans `app.config.ts`, à la place de `preset: Aura`) :
```ts
const LibraryPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{violet.50}', 100: '{violet.100}', 200: '{violet.200}', 300: '{violet.300}', 400: '{violet.400}',
      500: '{violet.500}', 600: '{violet.600}', 700: '{violet.700}', 800: '{violet.800}', 900: '{violet.900}',
      950: '{violet.950}',
    },
    colorScheme: {
      light: {
        primary: { color: '{primary.600}', contrastColor: '#ffffff', hoverColor: '{primary.700}', activeColor: '{primary.800}' },
        highlight: { background: '{primary.50}', focusBackground: '{primary.100}', color: '{primary.700}', focusColor: '{primary.800}' },
      },
    },
  },
  // Contraste AA (validé le 2026-11-26, écran « Mes emprunts ») : le texte `warn` / `success` d'Aura est sous 4,5:1.
  // Jetons de la version installée de @primeuix/themes : `message.<sévérité>.color`, `toast.<sévérité>.color` / `detailColor`.
  components: {
    message: {
      warn: { color: 'light-dark({yellow.800}, {yellow.500})' },
      success: { color: 'light-dark({green.800}, {green.500})' },
    },
    toast: {
      warn: {
        color: 'light-dark({yellow.800}, {yellow.500})',
        detailColor: 'light-dark({yellow.800}, {yellow.500})',
      },
      success: {
        color: 'light-dark({green.800}, {green.500})',
        detailColor: 'light-dark({green.800}, {green.500})',
      },
    },
  },
});
```
La surcharge `warn` / `success` s'applique à **tous** les toasts et messages de l'application, y compris les toasts
existants « Livre enregistré » (success) et « Session expirée » (warn). Seul le texte change (`{yellow.800}` et
`{green.800}` en clair, valeur d'origine en sombre) ; fonds et bordures Aura sont inchangés.
Le dégradé de l'en-tête est un style de `header.scss` (`background: var(--app-header-bg)`), avec
`--app-header-bg: linear-gradient(90deg, var(--p-primary-600), var(--p-fuchsia-600))` défini dans `styles.scss`.

## 2. Typographie
Aura ne fixe pas de police et le reboot Bootstrap n'est pas chargé : sans règle globale, le navigateur affiche une
police à empattements. **Règle globale** : dans `src/styles.scss`, `body { margin: 0; font-family: var(--app-font);
color: var(--p-text-color); }` avec la pile système, sans police distante :
`--app-font: system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`.

| Niveau | Taille | Graisse | Usage |
|---|---|---|---|
| Titre de page (`h1`) | 1.75rem (28 px), 1.5rem (24 px) sur mobile | 600 | « Catalogue » |
| Titre de dialogue | 1.25rem (20 px) | 600 | en-tête `p-dialog` (valeur Aura) |
| Titre de l'application | 1.25rem (20 px) | 600 | en-tête (existant) |
| Corps | 1rem (16 px), interligne 1.5 | 400 | cellules, champs, boutons |
| Libellé de champ | 1rem | 500 | `<label>` |
| Petit texte | 0.875rem (14 px) | 400 | aides de saisie, erreurs, compteurs, sous-lignes mobiles |

Chiffres tabulaires (`font-variant-numeric: tabular-nums`) pour l'ISBN et les exemplaires.

## 3. Espacements, grille, rayons, ombres
- **Échelle 4 px** : 4, 8, 12, 16, 24, 32, 48 (utilitaires Bootstrap `1`=4 px, `2`=8 px, `3`=16 px, `4`=24 px, `5`=48 px).
- **Marges de page** : 16 px sur mobile (`px-3`), 24 px à partir de `md` (`px-md-4`), conteneur `container-xl`
  (largeur max 1140 px à `xl`, 1320 px à `xxl`). En-tête et contenu alignés sur les mêmes marges.
- **Points de rupture** : ceux de Bootstrap (`sm` 576, `md` 768, `lg` 992, `xl` 1200). Conception mobile d'abord (390 px).
- **Rayons** (Aura) : 6 px champs, boutons, tableaux (`--p-border-radius-md`) ; 12 px dialogues (`--p-border-radius-xl`).
- **Ombres** (Aura) : aucune sur la page ; dialogue `0 20px 25px -5px rgba(0,0,0,.1), 0 8px 10px -6px rgba(0,0,0,.1)` ;
  masque modal `rgba(0,0,0,.4)`.
- **Focus** : anneau Aura (`focusRing` : 1 px plein `--p-primary-color`, décalage 2 px) sur tout élément focalisable ;
  jamais `outline: none` sans remplacement. Sur l'en-tête (fond en dégradé), anneau blanc 2 px. Les maquettes
  dessinent l'anneau en 2 px : `focusRing.width: '2px'` dans le preset (`semantic.focusRing`) pour s'y conformer.

## 4. Composants de base
| Besoin | Composant | Règles d'usage |
|---|---|---|
| Action principale | `p-button` (sévérité par défaut) | une seule par écran ; icône PrimeIcons à gauche si utile |
| Action secondaire | `p-button severity="secondary"` (`text` ou `outlined`) | Annuler, Réessayer, Effacer la recherche |
| Action destructive | `p-button severity="danger"` | toujours avec confirmation (`p-confirmdialog`) ; pas encore utilisé |
| Champ texte | `input pInputText` + `<label for>` visible | libellé au-dessus, aide en dessous (`small`, texte secondaire) |
| Champ avec icône | `p-iconfield` + `p-inputicon` | recherche (loupe à gauche) ; bouton d'effacement `p-button` texte à droite |
| Nombre | `p-inputnumber` | `[useGrouping]="false"`, `[showButtons]="true"`, bornes `min`/`max` |
| Erreur de champ | `p-message severity="error" variant="simple" size="small"` | sous le champ, avec `id` repris par `aria-describedby` |
| Erreur générale de formulaire | `p-message severity="error"` | en tête du formulaire, `role="alert"` |
| Tableau de données | `p-table` (`[lazy]="true"` pour la pagination serveur) | pas de tri client ; `caption` ou `aria-label` obligatoire |
| Pagination | `p-paginator` | options 10 / 20 / 50 ; masqué s'il n'y a aucun résultat |
| Chargement | `p-skeleton` (premier chargement), `[loading]` de `p-table` (rechargement) | jamais d'écran blanc |
| Notification | `p-toast` unique (racine), `MessageService` | succès bref (3 s) ; erreurs techniques via `errorInterceptor` |
| Dialogue | `p-dialog [modal]="true"` | formulaire court ; `draggable=false`, `resizable=false`, Échap ferme |
| Badge de rôle | `p-tag` | existant dans l'en-tête |
| Badge d'état | `p-tag` | statut d'une ligne : `severity="info"` (`sky.100` / `sky.700`, ex. « En cours ») ; `severity="danger"` (`red.100` / `red.700`, ex. « En retard ») avec icône `pi pi-exclamation-circle` ; le texte porte toujours l'information, jamais la seule couleur |
| Bandeau d'avertissement | `p-message severity="warn"` | information persistante de page (ex. retard bloquant l'emprunt), non fermable, sans rôle d'alerte s'il est présent dès l'affichage |
| Confirmation | `p-confirmdialog` | action sur une donnée (ex. « Rendre ») : bouton « Annuler » secondaire `text` à gauche, action principale à droite, focus initial sur « Annuler » (`defaultFocus="reject"`) |

### Icônes
- **PrimeIcons uniquement** (`<i class="pi pi-…" aria-hidden="true"></i>` ou propriété `icon` des composants) : icônes
  vectorielles au trait, cohérentes avec Aura. **Jamais d'emoji ni de caractère typographique** (« × », « + », « ⚠ »,
  « ↻ »…) utilisé comme icône, ni dans le code ni dans les maquettes.
- Taille : celle du texte voisin (1em) ; 28 px dans la pastille de 64 px des états vides et d'erreur.
- Une icône seule dans un bouton porte un `aria-label` ; une icône à côté d'un texte est décorative (`aria-hidden`).
- Correspondance utilisée par le catalogue :

| Usage | Classe PrimeIcons |
|---|---|
| Logo de l'application, catalogue vide | `pi pi-book` |
| Recherche, recherche sans résultat | `pi pi-search` |
| Effacer, fermer | `pi pi-times` |
| Ajouter, augmenter | `pi pi-plus` |
| Diminuer | `pi pi-minus` |
| Enregistrer | `pi pi-check` |
| Toast de succès | `pi pi-check-circle` (icône native du toast `success`) |
| Erreur de champ, message d'erreur | `pi pi-times-circle` (icône native de `p-message` `error`) |
| Erreur de chargement, bandeau et toast d'avertissement | `pi pi-exclamation-triangle` |
| Tag d'état en retard | `pi pi-exclamation-circle` |
| Mes emprunts vides, parcourir le catalogue | `pi pi-book` |
| Réessayer | `pi pi-refresh` |
| Chargement | `pi pi-spinner` (`pi-spin`) |
| Se déconnecter | `pi pi-sign-out` |
| Paginateur | `pi pi-angle-double-left`, `pi pi-angle-left`, `pi pi-angle-right`, `pi pi-angle-double-right`, `pi pi-chevron-down` (icônes natives de `p-paginator`) |

Les maquettes dessinent ces icônes en SVG intégré (planche de symboles interne au fichier) pour rester autonomes.

**À ne pas utiliser** : composants Bootstrap (`.btn`, `.card`, `.modal`, `.form-control`, `.form-label`, `.text-danger`)
— seules la grille et les utilitaires d'espacement/flex/affichage le sont ; couleurs codées en dur (toujours un jeton
`--p-*`) ; `p-table` avec défilement horizontal de page.

## 5. Ton des textes
- Français, **vouvoiement**.
- Libellés d'action à l'infinitif : « Ajouter un livre », « Enregistrer », « Annuler », « Réessayer », « Effacer la recherche ».
- Messages d'erreur qui disent quoi faire : « Saisissez le titre du livre. » plutôt que « Champ invalide ».
- Pas de jargon technique (« 500 », « ProblemDetail ») dans l'interface.
- Guillemets français « » dans les textes rédigés ; les guillemets droits "…" sont conservés là où la spec les impose
  (« Aucun livre ne correspond à "camus" »).
