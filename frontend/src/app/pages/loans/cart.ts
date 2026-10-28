import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { Messages } from '../../core/messages';
import { CartChange, CartView, Item } from '../../core/models';
import { Errors, ItemRow } from '../../shared/ui';

@Component({
  selector: 'app-cart',
  imports: [FormsModule, RouterLink, ItemRow, Errors],
  template: `
    <div class="page-head">
      <div class="titles">
        <h1>Panier</h1>
        <p class="subtitle">Vérifiez les quantités, puis envoyez la demande : l'administrateur réservera chaque article.</p>
      </div>
    </div>
    <app-errors [errors]="errors()" />

    @if (cart(); as c) {
      @if (c.items.length) {
        <ul class="list">
          @for (item of c.items; track item.id) {
            <app-item-row [item]="item" [showInCart]="false" [showPath]="true">
              <span class="row-actions">
                @if (item.available > 1) {
                  <input type="number" class="qty" min="1" [max]="item.available" inputmode="numeric"
                         [ngModel]="item.inCart" (change)="update(item, $any($event.target).value)"
                         [attr.aria-label]="'Quantité de ' + item.name">
                } @else {
                  <span class="muted" style="padding: 0 8px">× 1</span>
                }
                <button class="round ghost" type="button" (click)="update(item, 0)" [attr.aria-label]="'Retirer ' + item.name" title="Retirer">×</button>
              </span>
            </app-item-row>
          }
        </ul>
        <div class="panel-actions">
          <button class="btn block" type="button" (click)="submit()" [disabled]="busy()">
            {{ busy() ? 'Envoi…' : 'Envoyer la demande' }}
          </button>
          <a routerLink="/materiel" class="btn ghost block">Continuer à choisir</a>
        </div>
      } @else {
        <div class="panel empty">
          <p>Votre panier est vide.</p>
          <a routerLink="/materiel" class="btn">Parcourir le matériel</a>
        </div>
      }
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class Cart implements OnInit {
  private api = inject(Api);
  private auth = inject(Auth);
  private router = inject(Router);
  private messages = inject(Messages);

  cart = signal<CartView | null>(null);
  errors = signal<string[]>([]);
  busy = signal(false);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.api.get<CartView>('/panier').subscribe((cart) => {
      this.cart.set(cart);
      this.auth.setCartCount(cart.items.length);
      if (cart.removed) {
        this.messages.warning('Des articles qui ne sont plus disponibles ont été retirés de votre panier.');
      }
    });
  }

  update(item: Item, value: number | string): void {
    const quantity = Number(value) || 0;
    this.api.put<CartChange>(`/panier/${item.id}`, { quantity }).subscribe({
      next: (change) => {
        if (change.message) {
          this.messages.warning(change.message);
        }
        this.load();
      },
      error: (e) => this.messages.errors(errorMessages(e)),
    });
  }

  submit(): void {
    this.busy.set(true);
    this.errors.set([]);
    this.api.post<{ id: number; mailOk: boolean }>('/panier/envoyer').subscribe({
      next: (result) => {
        this.auth.setCartCount(0);
        this.messages.success("Votre demande est envoyée. L'administrateur va la traiter.");
        if (!result.mailOk) {
          this.messages.warning("La demande est bien enregistrée, mais l'e-mail de notification n'a pas pu partir.");
        }
        this.router.navigate(['/demandes', result.id]);
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }
}
