import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { FrDatePipe } from '../../core/format';
import { Account } from '../../core/models';

const ROLES = [
  { key: 'tous', label: 'Tous' },
  { key: 'invites', label: 'Invités' },
  { key: 'admins', label: 'Administrateurs' },
  { key: 'inactifs', label: 'Désactivés' },
];

@Component({
  selector: 'app-accounts',
  imports: [FormsModule, RouterLink, FrDatePipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head">
      <div class="titles">
        <h1>Comptes</h1>
        <p class="subtitle">Pas d'inscription publique : créez le compte, l'invité reçoit un lien pour choisir son mot de passe.</p>
      </div>
      <div class="actions"><a class="btn small" routerLink="/gestion/comptes/nouveau">+ Nouveau compte</a></div>
    </div>

    <div class="search">
      <input type="search" [ngModel]="query()" (ngModelChange)="query.set($event)" placeholder="Nom, identifiant, e-mail…" aria-label="Rechercher">
    </div>
    <div class="chips">
      @for (r of roles; track r.key) {
        <button type="button" class="chip" [class.active]="role() === r.key" (click)="role.set(r.key)">{{ r.label }}</button>
      }
    </div>

    @if (accounts()) {
      <ul class="list">
        @for (a of filtered(); track a.id) {
          <li class="row" [class.inactive]="!a.active">
            <a class="row-main" [routerLink]="['/gestion/comptes', a.id]">
              <span class="row-title">{{ a.displayName }}</span>
              <span class="row-meta">
                <span>{{ a.username }}</span>
                <span class="hide-small">{{ a.email }}</span>
                <span>{{ a.lastLogin ? 'Vu le ' + (a.lastLogin | frDate) : 'Jamais connecté' }}</span>
              </span>
            </a>
            <span class="row-status">
              <span class="badge" [class]="a.admin ? 'badge-info' : 'badge-muted'">{{ a.admin ? 'Admin' : 'Invité' }}</span>
              @if (!a.active) { <span class="badge badge-danger">Désactivé</span> }
              @else if (!a.hasPassword) { <span class="badge badge-warn">Invitation en attente</span> }
            </span>
            <a class="round ghost" [routerLink]="['/gestion/comptes', a.id]" [attr.aria-label]="'Modifier ' + a.displayName" title="Modifier">✎</a>
          </li>
        } @empty {
          <li class="empty">Aucun compte.</li>
        }
      </ul>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class Accounts implements OnInit {
  private api = inject(Api);
  roles = ROLES;
  accounts = signal<Account[] | null>(null);
  role = signal('tous');
  query = signal('');

  filtered = computed(() => {
    const words = normalize(this.query()).split(' ').filter(Boolean);
    return (this.accounts() ?? []).filter((a) => {
      const role = this.role();
      if ((role === 'invites' && (a.admin || !a.active)) || (role === 'admins' && !a.admin) || (role === 'inactifs' && a.active)) {
        return false;
      }
      const text = normalize(`${a.displayName} ${a.username} ${a.email}`);
      return words.every((w) => text.includes(w));
    });
  });

  ngOnInit(): void {
    this.api.get<Account[]>('/gestion/comptes').subscribe((list) => this.accounts.set(list));
  }
}

/** Minuscules sans accents, comme la recherche du matériel. */
function normalize(value: string): string {
  return value.normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}
