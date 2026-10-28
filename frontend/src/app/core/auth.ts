import { Injectable, computed, inject, signal } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Observable, catchError, map, of, tap } from 'rxjs';
import { Api } from './api';
import { Me } from './models';

/** Utilisateur connecté et compteurs de l'en-tête (panier, demandes à traiter). */
@Injectable({ providedIn: 'root' })
export class Auth {
  private api = inject(Api);
  private loaded = false;

  readonly me = signal<Me | null>(null);
  readonly isAdmin = computed(() => this.me()?.admin ?? false);
  readonly cartCount = computed(() => this.me()?.cartCount ?? 0);

  /** Charge le profil une fois (au premier passage dans un garde). */
  ensureLoaded(): Observable<Me | null> {
    if (this.loaded) {
      return of(this.me());
    }
    return this.refresh();
  }

  refresh(): Observable<Me | null> {
    return this.api.get<Me>('/auth/moi').pipe(
      catchError(() => of(null)),
      tap((me) => {
        this.loaded = true;
        this.me.set(me);
      }),
    );
  }

  /** Rafraîchit les compteurs sans attendre le résultat. */
  reload(): void {
    this.refresh().subscribe();
  }

  setCartCount(count: number): void {
    const me = this.me();
    if (me) {
      this.me.set({ ...me, cartCount: count });
    }
  }

  login(login: string, password: string): Observable<Me> {
    return this.api.post<Me>('/auth/connexion', { login, password }).pipe(tap((me) => this.me.set(me)));
  }

  setSession(me: Me): void {
    this.loaded = true;
    this.me.set(me);
  }

  logout(): Observable<unknown> {
    return this.api.post('/auth/deconnexion').pipe(tap(() => this.me.set(null)));
  }
}

/** Toute page exige une connexion (sauf celles déclarées sans garde). */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(Auth);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(
    map((me) => me ? true : router.createUrlTree(['/connexion'], { queryParams: { suite: state.url } })),
  );
};

/** Pages de gestion : réservées à l'admin (l'API refuse de toute façon les invités). */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map((me) => me?.admin ? true : router.createUrlTree(['/'])));
};

/** Pages de connexion : inutiles si l'on est déjà connecté. */
export const anonymousGuard: CanActivateFn = () => {
  const auth = inject(Auth);
  const router = inject(Router);
  return auth.ensureLoaded().pipe(map((me) => me ? router.createUrlTree(['/']) : true));
};
