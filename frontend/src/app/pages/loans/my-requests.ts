import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { LoanRequest } from '../../core/models';
import { RequestRow } from '../../shared/request-card';

@Component({
  selector: 'app-my-requests',
  imports: [RouterLink, RequestRow],
  template: `
    <div class="page-head">
      <div class="titles">
        <h1>Mes demandes</h1>
        <p class="subtitle">L'administrateur réserve, refuse ou marque rendu chaque article : suivez-en l'état ici.</p>
      </div>
      <div class="actions"><a routerLink="/materiel" class="btn secondary small">+ Nouvelle demande</a></div>
    </div>
    @if (requests(); as list) {
      <ul class="list">
        @for (r of list; track r.id) {
          <app-request-row [request]="r" />
        } @empty {
          <li class="empty">Aucune demande pour le moment.</li>
        }
      </ul>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class MyRequests implements OnInit {
  private api = inject(Api);
  requests = signal<LoanRequest[] | null>(null);

  ngOnInit(): void {
    this.api.get<LoanRequest[]>('/demandes').subscribe((list) => this.requests.set(list));
  }
}
