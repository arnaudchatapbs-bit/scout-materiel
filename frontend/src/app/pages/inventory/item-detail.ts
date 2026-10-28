import { Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { FrDatePipe, PluralPipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { CartChange, ItemPage } from '../../core/models';
import { Availability, Breadcrumb } from '../../shared/ui';

@Component({
  selector: 'app-item-detail',
  imports: [FormsModule, RouterLink, Breadcrumb, Availability, PluralPipe, FrDatePipe],
  template: `
    @if (page(); as p) {
      @let it = p.item;
      <app-breadcrumb [crumbs]="p.breadcrumb" [linkLast]="true" />
      <div class="page-head">
        <div class="titles">
          <h1>{{ it.name }}</h1>
          <p class="subtitle"><app-availability [item]="it" [admin]="auth.isAdmin()" /></p>
        </div>
        @if (auth.isAdmin()) {
          <div class="actions"><a class="btn secondary small" [routerLink]="['/gestion/materiel', it.id]">✎ Modifier</a></div>
        }
      </div>

      <div class="panel">
        <dl class="details">
          @if (it.reference) { <dt>Référence</dt><dd class="ref">{{ it.reference }}</dd> }
          <dt>Quantité</dt>
          <dd>
            @if (!it.reservable) {
              {{ it.quantity }} (consommable, non réservable)
            } @else if (auth.isAdmin()) {
              {{ it.quantity }} au total · {{ it.reserved | plural: 'réservé' }} · {{ it.available | plural: 'disponible' }}
            } @else {
              {{ it.available | plural: 'disponible' }}
            }
          </dd>
          @if (it.location) { <dt>Localisation</dt><dd>📍 {{ it.location }}</dd> }
          @if (it.comment) { <dt>Commentaire</dt><dd>{{ it.comment }}</dd> }
          @if (auth.isAdmin() && it.remark) { <dt>Remarque d'import</dt><dd>{{ it.remark }}</dd> }
        </dl>

        @if (it.inCart) {
          <p style="margin: 16px 0 0">🛒 {{ it.inCart }} déjà dans <a routerLink="/panier">votre panier</a>.</p>
        }
        @if (it.addable) {
          <form class="panel-actions" (ngSubmit)="add()">
            @if (it.addable > 1) {
              <label class="field" style="margin: 0; flex: 0 0 6rem">
                <span class="small">Quantité</span>
                <input type="number" name="quantity" [(ngModel)]="quantity" min="1" [max]="it.addable" inputmode="numeric" class="qty">
              </label>
            }
            <button class="btn" type="submit" style="flex: 1; align-self: flex-end" [disabled]="busy()">Ajouter au panier</button>
          </form>
        }
      </div>

      @if (auth.isAdmin()) {
        <h2 class="section-title">Réservations en cours</h2>
        <ul class="list">
          @for (line of p.reservedLines; track line.requestId) {
            <li class="row">
              <a class="row-main" [routerLink]="['/gestion/demandes', line.requestId]">
                <span class="row-title">{{ line.quantity }} pour {{ line.requesterName }}</span>
                <span class="row-meta">Demande n°{{ line.requestId }}@if (line.date) { · réservé le {{ line.date | frDate }} }</span>
              </a>
            </li>
          } @empty {
            <li class="empty">Aucune réservation en cours.</li>
          }
        </ul>
        @if (p.pendingLines?.length) {
          <h2 class="section-title">Demandes en attente</h2>
          <ul class="list">
            @for (line of p.pendingLines; track line.requestId) {
              <li class="row">
                <a class="row-main" [routerLink]="['/gestion/demandes', line.requestId]">
                  <span class="row-title">{{ line.quantity }} demandé{{ line.quantity > 1 ? 's' : '' }} par {{ line.requesterName }}</span>
                  <span class="row-meta">Demande n°{{ line.requestId }} · le {{ line.date | frDate }}</span>
                </a>
              </li>
            }
          </ul>
        }
      }
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class ItemDetail {
  private api = inject(Api);
  private router = inject(Router);
  private messages = inject(Messages);
  protected auth = inject(Auth);
  id = input.required<string>();

  page = signal<ItemPage | null>(null);
  busy = signal(false);
  quantity = 1;

  constructor() {
    effect(() => this.load(this.id()));
  }

  load(id: string): void {
    this.api.get<ItemPage>(`/materiel/${id}`).subscribe({
      next: (page) => {
        this.page.set(page);
        this.quantity = 1;
      },
      error: (e) => {
        this.messages.errors(errorMessages(e));
        this.router.navigateByUrl('/materiel');
      },
    });
  }

  add(): void {
    const item = this.page()!.item;
    this.busy.set(true);
    this.api.post<CartChange>('/panier', { item: item.id, quantity: this.quantity || 1 }).subscribe({
      next: (change) => {
        this.busy.set(false);
        this.auth.setCartCount(change.cartCount);
        if (change.limited) {
          this.messages.warning(change.message!);
        } else {
          this.messages.success(change.message!);
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
