/** Emprunt en cours tel que renvoyé par `GET /api/v1/loans` (DTO `CurrentLoan`). Dates `yyyy-MM-dd`, sans fuseau. */
export interface CurrentLoan {
  id: string;
  bookId: string;
  /** `null` si le livre n'existe plus au catalogue. */
  isbn: string | null;
  title: string | null;
  author: string | null;
  borrowedOn: string;
  dueOn: string;
  /** Calculé par le backend : l'écran ne compare jamais de dates. */
  overdue: boolean;
}

/** Limite d'emprunts actifs (RM-01) : affichage du compteur seulement, le backend reste la référence. */
export const MAX_ACTIVE_LOANS = 3;
