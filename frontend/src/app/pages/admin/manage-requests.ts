import { Component, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LoanRequest } from '../../core/models';
import { RequestRow } from '../../shared/request-card';

const TABS = [
  { key: 'a-traiter', label: 'À traiter' },
  { key: 'en-cours', label: 'Réservées' },
  { key: 'toutes', label: 'Toutes' },
];

@Component({
  selector: 'app-manage-requests',
  imports: [RouterLink, RequestRow],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head"><div class="titles"><h1>Demandes</h1></div></div>
    <div class="chips" role="tablist">
      @for (tab of tabs; track tab.key) {
        <a class="chip" role="tab" [class.active]="current() === tab.key" [attr.aria-selected]="current() === tab.key"
           routerLink="/gestion/demandes" [queryParams]="{ onglet: tab.key }">{{ tab.label }}</a>
      }
    </div>
    @if (requests(); as list) {
      <ul class="list">
        @for (r of list; track r.id) {
          <app-request-row [request]="r" [showRequester]="true" base="/gestion/demandes" />
        } @empty {
          <li class="empty">{{ current() === 'a-traiter' ? 'Aucune demande en attente.' : 'Aucune demande.' }}</li>
        }
      </ul>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class ManageRequests {
  private api = inject(Api);
  onglet = input<string>();
  tabs = TABS;
  current = signal('a-traiter');
  requests = signal<LoanRequest[] | null>(null);

  constructor() {
    effect(() => {
      const tab = TABS.some((t) => t.key === this.onglet()) ? this.onglet()! : 'a-traiter';
      this.current.set(tab);
      this.requests.set(null);
      this.api.get<LoanRequest[]>('/gestion/demandes', { onglet: tab }).subscribe((list) => this.requests.set(list));
    });
  }
}
