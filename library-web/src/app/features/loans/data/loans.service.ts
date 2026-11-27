import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { skipErrorToast } from '../../../core/http/error-toast.context';
import { CurrentLoan } from './loans.model';

/** Seul accès HTTP de la feature (contrat : `docs/api/loan.md`). */
@Injectable({ providedIn: 'root' })
export class LoansService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/loans';

  /** Emprunts en cours du membre connecté, déjà triés par le backend (au plus 3). */
  listMine(): Observable<CurrentLoan[]> {
    return this.http.get<CurrentLoan[]>(this.baseUrl);
  }

  /** Rend un emprunt (204). 404, 409 et 503 sont notifiés par l'écran : pas de notification de l'intercepteur. */
  giveBack(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${encodeURIComponent(id)}/return`, null, {
      context: skipErrorToast(404, 409, 503),
    });
  }
}
