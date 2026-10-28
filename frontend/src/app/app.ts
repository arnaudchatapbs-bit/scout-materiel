import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Auth } from './core/auth';
import { Messages } from './core/messages';

/** Coquille : barre du haut (ordinateur), barre du bas (téléphone), messages. */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    @if (auth.me(); as me) {
      <header class="topbar">
        <div class="container">
          <a routerLink="/" class="brand">⛺ Scout Matériel</a>
          <nav class="topnav">
            <a class="wide" routerLink="/materiel" routerLinkActive="active">Matériel</a>
            <a class="wide" routerLink="/demandes" routerLinkActive="active">Mes demandes</a>
            @if (me.admin) {
              <a class="wide" routerLink="/gestion" routerLinkActive="active">
                Gestion @if (me.pendingRequests) { ({{ me.pendingRequests }}) }
              </a>
            }
            <a routerLink="/panier" class="icon-link" [attr.aria-label]="'Panier, ' + me.cartCount + ' article(s)'">
              🛒 @if (me.cartCount) { <span class="count">{{ me.cartCount }}</span> }
            </a>
            <button type="button" class="link" (click)="logout()">Déconnexion</button>
          </nav>
        </div>
      </header>

      <div class="bottombar">
        <nav aria-label="Navigation principale">
          <a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }"><span class="ico">🏠</span>Accueil</a>
          <a routerLink="/materiel" routerLinkActive="active"><span class="ico">📦</span>Matériel</a>
          <a routerLink="/panier" routerLinkActive="active">
            <span class="ico">🛒@if (me.cartCount) { <span class="count">{{ me.cartCount }}</span> }</span>Panier
          </a>
          <a routerLink="/demandes" routerLinkActive="active"><span class="ico">📝</span>Demandes</a>
          @if (me.admin) {
            <a routerLink="/gestion" routerLinkActive="active">
              <span class="ico">⚙️@if (me.pendingRequests) { <span class="count">{{ me.pendingRequests }}</span> }</span>Gestion
            </a>
          }
        </nav>
      </div>
    }

    <main class="container">
      <router-outlet />
    </main>

    <div class="toasts" aria-live="polite">
      <div class="container">
        @for (m of messages.list(); track m.id) {
          <div class="toast" [class]="m.kind" role="status">
            <p>{{ m.text }}</p>
            <button type="button" (click)="messages.dismiss(m.id)" aria-label="Fermer">×</button>
          </div>
        }
      </div>
    </div>
  `,
})
export class App {
  protected auth = inject(Auth);
  protected messages = inject(Messages);
  private router = inject(Router);

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigateByUrl('/connexion'));
  }
}
