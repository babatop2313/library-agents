import { HttpErrorResponse } from '@angular/common/http';

import { LOAN_BUSY_MESSAGE, returnErrorMessage } from './return-errors';

function error(status: number, body: unknown = null): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

function problem(status: number, code: string): HttpErrorResponse {
  return error(status, { title: 'Titre', status, code });
}

/** RM-LOAN-25 : message par code du contrat pour un refus de retour, repli sur le statut. */
describe('returnErrorMessage', () => {
  it.each([
    ['LOAN_ALREADY_RETURNED', 409, 'Cet emprunt est déjà rendu'],
    ['LOAN_NOT_FOUND', 404, 'Emprunt introuvable'],
    ['LOAN_BUSY', 503, 'Le service est momentanément occupé, réessayez'],
  ])('traduit le code %s (%i)', (code, status, message) => {
    expect(returnErrorMessage(problem(status as number, code as string))).toBe(message);
  });

  it('expose le message LOAN_BUSY du contrat', () => {
    expect(LOAN_BUSY_MESSAGE).toBe('Le service est momentanément occupé, réessayez');
  });

  it.each([
    [404, 'Emprunt introuvable'],
    [409, 'Cet emprunt est déjà rendu'],
    [503, 'Le service est momentanément occupé, réessayez'],
  ])('se rabat sur le statut %i sans corps exploitable', (status, message) => {
    expect(returnErrorMessage(error(status))).toBe(message);
  });

  it('se rabat sur le statut pour un code inconnu', () => {
    expect(returnErrorMessage(problem(404, 'AUTRE'))).toBe('Emprunt introuvable');
  });

  it.each([0, 400, 401, 403, 500, 502])('renvoie null pour un %i (intercepteur)', (status) => {
    expect(returnErrorMessage(error(status))).toBeNull();
  });

  it('renvoie null pour une erreur qui n’est pas HTTP', () => {
    expect(returnErrorMessage(new Error('boom'))).toBeNull();
  });

  it.each(['constructor', 'toString', '__proto__', 'hasOwnProperty'])(
    'traite le code hérité « %s » comme inconnu : repli sur le statut, jamais une fonction',
    (code) => {
      const result = returnErrorMessage(problem(409, code));

      expect(typeof result === 'function').toBe(false);
      expect(result).toEqual(returnErrorMessage(error(409)));
    },
  );
});
