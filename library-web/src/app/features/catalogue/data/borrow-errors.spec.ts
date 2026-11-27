import { HttpErrorResponse } from '@angular/common/http';

import { borrowFailure } from './borrow-errors';

function error(status: number, body: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

function problem(status: number, code: string): HttpErrorResponse {
  return error(status, { title: 'Titre', status, code });
}

/** RM-LOAN-22 : un message métier par code du contrat, repli sur le statut. */
describe('borrowFailure', () => {
  it.each([
    ['BOOK_NOT_AVAILABLE', 409, "Ce livre n'est plus disponible", true],
    ['LOAN_LIMIT_REACHED', 409, 'Vous avez atteint la limite de 3 emprunts en cours', false],
    ['MEMBER_HAS_OVERDUE_LOAN', 409, "Rendez d'abord vos livres en retard", false],
    ['BOOK_ALREADY_BORROWED', 409, 'Vous avez déjà emprunté ce livre', false],
    ['BOOK_NOT_FOUND', 404, "Ce livre n'existe plus", true],
    ['LOAN_BUSY', 503, 'Le service est momentanément occupé, réessayez', false],
  ])('traduit le code %s (%i) en message, rechargement : %s', (code, status, message, reload) => {
    expect(borrowFailure(problem(status as number, code as string))).toEqual({ message, reload });
  });

  it('privilégie le code sur le statut', () => {
    expect(borrowFailure(problem(409, 'LOAN_LIMIT_REACHED'))?.reload).toBe(false);
  });

  it.each([
    [404, "Ce livre n'existe plus", true],
    [409, "Cet emprunt n'a pas pu être enregistré", true],
    [503, 'Le service est momentanément occupé, réessayez', false],
  ])('se rabat sur le statut %i sans corps exploitable', (status, message, reload) => {
    expect(borrowFailure(error(status))).toEqual({ message, reload });
  });

  it('se rabat sur le statut pour un code inconnu', () => {
    expect(borrowFailure(problem(409, 'CODE_INCONNU'))?.message).toBe(
      "Cet emprunt n'a pas pu être enregistré",
    );
  });

  it.each([0, 400, 401, 403, 500, 502])('renvoie null pour un %i (intercepteur)', (status) => {
    expect(borrowFailure(error(status))).toBeNull();
  });

  it('renvoie null pour une erreur qui n’est pas HTTP', () => {
    expect(borrowFailure(new Error('boom'))).toBeNull();
    expect(borrowFailure(undefined)).toBeNull();
  });

  it.each(['constructor', 'toString', '__proto__', 'hasOwnProperty'])(
    'traite le code hérité « %s » comme inconnu : repli sur le statut, jamais une fonction',
    (code) => {
      const result = borrowFailure(problem(409, code));

      expect(typeof result === 'function').toBe(false);
      expect(result).toEqual(borrowFailure(error(409)));
    },
  );
});
