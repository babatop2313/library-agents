import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { skipErrorToast } from '../../../core/http/error-toast.context';
import { Page } from '../../../core/http/page';
import { Book, BookSearchParams, RegisterBookRequest } from './catalogue.model';

/** Seul accès HTTP de la feature (contrat : `docs/api/catalogue.md`). */
@Injectable({ providedIn: 'root' })
export class CatalogueService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/books';

  /** Livres disponibles, paginés ; `q` est omis s'il est vide. */
  search(params: BookSearchParams): Observable<Page<Book>> {
    let httpParams = new HttpParams().set('page', params.page).set('size', params.size);
    if (params.q) {
      httpParams = httpParams.set('q', params.q);
    }
    return this.http.get<Page<Book>>(this.baseUrl, { params: httpParams });
  }

  /** Enregistre un livre ; les erreurs 400 et 409 sont reportées sur le formulaire, sans notification. */
  register(body: RegisterBookRequest): Observable<Book> {
    return this.http.post<Book>(this.baseUrl, body, { context: skipErrorToast(400, 409) });
  }
}
