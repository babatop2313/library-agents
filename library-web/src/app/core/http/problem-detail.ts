/** Erreur sur un champ, telle que renvoyée par le backend dans la propriété `errors`. */
export interface FieldError {
  field: string;
  code: string;
  message: string;
}

/** Corps d'erreur RFC 9457 renvoyé par le backend (ProblemDetail + propriétés `code` et `errors`). */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: FieldError[];
}

export function isProblemDetail(value: unknown): value is ProblemDetail {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Record<string, unknown>;
  return typeof candidate['title'] === 'string' || typeof candidate['detail'] === 'string';
}
