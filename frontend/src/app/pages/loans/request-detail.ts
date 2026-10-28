import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { FrDatePipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { LoanRequest } from '../../core/models';
import { LineStatusBadge, RequestStatusBadge } from '../../shared/ui';

@Component({
  selector: 'app-request-detail',
  imports: [RouterLink, LineStatusBadge, RequestStatusBadge, FrDatePipe],
  template: `
    <nav class="crumbs"><a routerLink="/demandes">Mes demandes</a></nav>
    @if (request(); as r) {
      <div class="page-head">
        <div class="titles">
          <h1>Demande n°{{ r.id }}</h1>
          <p class="subtitle">Envoyée le {{ r.createdAt | frDate: true }} · <app-request-status [status]="r.status" /></p>
        </div>
      </div>
      <ul class="list">
        @for (line of r.lines; track line.id) {
          <li class="row">
            <a class="row-main" [routerLink]="['/materiel', line.itemId]">
              <span class="row-title">{{ line.itemName }} × {{ line.quantity }}</span>
              <span class="row-meta">
                @if (line.itemReference) { <span class="ref">{{ line.itemReference }}</span> }
                @if (line.itemLocation) { <span>📍 {{ line.itemLocation }}</span> }
              </span>
              @if (line.adminComment) { <span class="row-meta">💬 {{ line.adminComment }}</span> }
            </a>
            <span class="row-status"><app-line-status [status]="line.status" /></span>
          </li>
        }
      </ul>
      @if (canCancel()) {
        <div class="panel-actions">
          <button class="btn danger block" type="button" (click)="cancel()" [disabled]="busy()">Annuler les articles en attente</button>
        </div>
      }
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class RequestDetail {
  private api = inject(Api);
  private router = inject(Router);
  private messages = inject(Messages);
  id = input.required<string>();

  request = signal<LoanRequest | null>(null);
  busy = signal(false);
  canCancel = computed(() => this.request()?.lines.some((l) => l.status === 'en_attente') ?? false);

  constructor() {
    effect(() => this.load(this.id()));
  }

  load(id: string): void {
    this.api.get<LoanRequest>(`/demandes/${id}`).subscribe({
      next: (r) => this.request.set(r),
      error: (e) => {
        this.messages.errors(errorMessages(e));
        this.router.navigateByUrl('/demandes');
      },
    });
  }

  cancel(): void {
    if (!confirm('Annuler les articles encore en attente de cette demande ?')) {
      return;
    }
    this.busy.set(true);
    this.api.post<{ mailOk: boolean }>(`/demandes/${this.id()}/annuler`).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.messages.success('Les articles en attente ont été annulés.');
        if (!result.mailOk) {
          this.messages.warning("L'annulation est enregistrée, mais l'e-mail à l'administrateur n'a pas pu partir.");
        }
        this.load(this.id());
      },
      error: (e) => {
        this.busy.set(false);
        this.messages.errors(errorMessages(e));
      },
    });
  }
}
