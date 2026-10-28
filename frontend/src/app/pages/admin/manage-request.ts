import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { FrDatePipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { LineStatus, LoanRequest, RequestLine } from '../../core/models';
import { Errors, LineStatusBadge, RequestStatusBadge } from '../../shared/ui';

/** Actions possibles selon le statut de la ligne (mêmes règles que LoanService.ALLOWED_FROM). */
const ACTIONS: Partial<Record<LineStatus, { value: string; label: string }[]>> = {
  en_attente: [{ value: 'reserve', label: 'Réserver' }, { value: 'refuse', label: 'Refuser' }],
  reservee: [{ value: 'return', label: 'Rendu' }, { value: 'unreserve', label: 'Annuler la réservation' }],
};

interface Draft {
  action: string;
  quantity: number;
  comment: string;
}

@Component({
  selector: 'app-manage-request',
  imports: [FormsModule, RouterLink, Errors, LineStatusBadge, RequestStatusBadge, FrDatePipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a><span class="sep">›</span><a routerLink="/gestion/demandes">Demandes</a></nav>
    @if (request(); as r) {
      <div class="page-head">
        <div class="titles">
          <h1>Demande n°{{ r.id }}</h1>
          <p class="subtitle">
            {{ r.requesterName }} · <a [href]="'mailto:' + r.requesterEmail">{{ r.requesterEmail }}</a><br>
            Envoyée le {{ r.createdAt | frDate: true }} · <app-request-status [status]="r.status" />
          </p>
        </div>
        @if (hasPending() || hasReserved()) {
          <div class="actions">
            @if (hasPending()) { <button class="btn small" type="button" (click)="bulk('reserve_all')" [disabled]="busy()">✅ Tout réserver</button> }
            @if (hasReserved()) { <button class="btn secondary small" type="button" (click)="bulk('return_all')" [disabled]="busy()">↩️ Tout marquer rendu</button> }
          </div>
        }
      </div>

      <app-errors [errors]="errors()" />

      <form (ngSubmit)="save()">
        <ul class="list">
          @for (line of r.lines; track line.id) {
            @let d = drafts()[line.id];
            <li class="row-block">
              <div class="row" style="padding: 0; border: 0">
                <a class="row-main" [routerLink]="['/materiel', line.itemId]">
                  <span class="row-title">{{ line.itemName }} × {{ line.quantity }}</span>
                  <span class="row-meta">
                    @if (line.itemReference) { <span class="ref">{{ line.itemReference }}</span> }
                    @if (line.itemLocation) { <span>📍 {{ line.itemLocation }}</span> }
                    <span>{{ line.itemAvailable }} / {{ line.itemQuantity }} disponible{{ (line.itemAvailable ?? 0) > 1 ? 's' : '' }}</span>
                  </span>
                </a>
                <span class="row-status"><app-line-status [status]="line.status" /></span>
              </div>
              @if (actions(line); as choices) {
                <div class="grid-2" style="margin-top: 10px">
                  <label class="field">
                    <span class="small">Décision</span>
                    <select [name]="'action-' + line.id" [(ngModel)]="d.action">
                      <option value="">— Ne rien changer —</option>
                      @for (a of choices; track a.value) { <option [value]="a.value">{{ a.label }}</option> }
                    </select>
                  </label>
                  @if (d.action === 'reserve' && line.quantity > 1) {
                    <label class="field">
                      <span class="small">Quantité réservée</span>
                      <input type="number" [name]="'qty-' + line.id" [(ngModel)]="d.quantity" min="1" [max]="line.quantity" inputmode="numeric">
                    </label>
                  }
                </div>
                <label class="field" style="margin-bottom: 0">
                  <span class="small">Commentaire pour l'invité</span>
                  <input type="text" [name]="'comment-' + line.id" [(ngModel)]="d.comment" maxlength="200" placeholder="Facultatif">
                </label>
              } @else if (line.adminComment) {
                <p class="row-meta" style="margin: 6px 0 0">💬 {{ line.adminComment }}</p>
              }
            </li>
          }
        </ul>
        @if (hasPending() || hasReserved()) {
          <div class="panel-actions">
            <button class="btn block" type="submit" [disabled]="busy()">Enregistrer les décisions</button>
          </div>
        }
      </form>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class ManageRequest {
  private api = inject(Api);
  private auth = inject(Auth);
  private router = inject(Router);
  private messages = inject(Messages);
  id = input.required<string>();

  request = signal<LoanRequest | null>(null);
  drafts = signal<Record<number, Draft>>({});
  errors = signal<string[]>([]);
  busy = signal(false);
  hasPending = computed(() => this.request()?.lines.some((l) => l.status === 'en_attente') ?? false);
  hasReserved = computed(() => this.request()?.lines.some((l) => l.status === 'reservee') ?? false);

  constructor() {
    effect(() => this.load(this.id()));
  }

  actions(line: RequestLine) {
    return ACTIONS[line.status] ?? null;
  }

  load(id: string): void {
    this.api.get<LoanRequest>(`/gestion/demandes/${id}`).subscribe({
      next: (r) => {
        const drafts: Record<number, Draft> = {};
        r.lines.forEach((l) => (drafts[l.id] = { action: '', quantity: l.quantity, comment: l.adminComment }));
        this.drafts.set(drafts);
        this.request.set(r);
      },
      error: (e) => {
        this.messages.errors(errorMessages(e));
        this.router.navigateByUrl('/gestion/demandes');
      },
    });
  }

  save(): void {
    const lines = this.request()!.lines;
    const decisions = lines
      .filter((l) => this.actions(l))
      .map((l) => {
        const d = this.drafts()[l.id];
        return { line: l.id, action: d.action, quantity: d.action === 'reserve' ? d.quantity : null, comment: d.comment };
      });
    this.send({ decisions });
  }

  bulk(kind: 'reserve_all' | 'return_all'): void {
    this.send({ bulk: kind });
  }

  private send(body: unknown): void {
    this.busy.set(true);
    this.errors.set([]);
    this.api.post<{ changed: number }>(`/gestion/demandes/${this.id()}/decisions`, body).subscribe({
      next: ({ changed }) => {
        this.busy.set(false);
        if (changed) {
          this.messages.success(`${changed} article${changed > 1 ? 's' : ''} mis à jour.`);
        } else {
          this.messages.info('Aucune modification.');
        }
        this.auth.reload();
        this.load(this.id());
      },
      error: (e) => {
        // Tout-ou-rien : rien n'a été enregistré, les choix restent à l'écran
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }
}
