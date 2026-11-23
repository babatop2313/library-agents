import { HttpErrorResponse } from '@angular/common/http';

import { ProblemDetail } from '../http/problem-detail';

/** Erreur HTTP simulée portant un `ProblemDetail`. */
export function problem(body: ProblemDetail): HttpErrorResponse {
  const status = body.status ?? 500;
  return new HttpErrorResponse({ status, statusText: statusText(status), error: body });
}

function statusText(status: number): string {
  const texts: Record<number, string> = {
    400: 'Bad Request',
    401: 'Unauthorized',
    403: 'Forbidden',
    404: 'Not Found',
    409: 'Conflict',
  };
  return texts[status] ?? 'Error';
}
