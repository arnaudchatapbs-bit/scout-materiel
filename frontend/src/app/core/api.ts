import { HttpClient, HttpErrorResponse, HttpInterceptorFn, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Ajoute l'en-tête exigé par le backend sur toutes les requêtes (protection CSRF). */
export const csrfInterceptor: HttpInterceptorFn = (req, next) =>
  next(req.clone({ setHeaders: { 'X-Requested-With': 'ScoutMateriel' } }));

/** Messages d'erreur en français renvoyés par l'API ({"errors": [...]}). */
export function errorMessages(error: unknown): string[] {
  if (error instanceof HttpErrorResponse) {
    const errors = error.error?.errors;
    if (Array.isArray(errors) && errors.length) {
      return errors;
    }
    if (error.status === 0) {
      return ['Le serveur ne répond pas : vérifiez la connexion.'];
    }
  }
  return ['Une erreur est survenue, réessayez.'];
}

type Params = Record<string, string | number | boolean | null | undefined>;

@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);

  get<T>(path: string, params: Params = {}): Observable<T> {
    let httpParams = new HttpParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== null && value !== undefined && value !== '') {
        httpParams = httpParams.set(key, String(value));
      }
    }
    return this.http.get<T>('/api' + path, { params: httpParams });
  }

  post<T>(path: string, body: unknown = {}): Observable<T> {
    return this.http.post<T>('/api' + path, body);
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>('/api' + path, body);
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>('/api' + path);
  }
}
