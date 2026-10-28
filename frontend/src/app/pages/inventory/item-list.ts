import { Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { PluralPipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { CartChange, Item, ListPage } from '../../core/models';
import { Breadcrumb, ItemRow } from '../../shared/ui';

/**
 * Page « Matériel » : une carte par famille, puis sous-catégories en puces et objets en lignes.
 * L'admin voit la même page, avec en plus le bouton « ✎ » sur chaque ligne et « Nouvel objet ».
 */
@Component({
  selector: 'app-item-list',
  imports: [FormsModule, RouterLink, Breadcrumb, ItemRow, PluralPipe],
  template: `
    @let p = page();
    @if (p?.category) {
      <app-breadcrumb [crumbs]="p!.breadcrumb" [linkLast]="!!q()" />
    }
    <div class="page-head">
      <div class="titles"><h1>{{ p?.category?.name ?? 'Matériel' }}</h1></div>
      @if (auth.isAdmin()) {
        <div class="actions">
          <a class="btn secondary small" routerLink="/gestion/materiel/nouveau" [queryParams]="{ cat: cat() }">+ Nouvel objet</a>
        </div>
      }
    </div>

    <form class="search" role="search" (ngSubmit)="search()">
      <input type="search" name="q" [(ngModel)]="query" [placeholder]="p?.category ? 'Rechercher dans ' + p!.category!.name : 'Rechercher un objet, une référence, un lieu…'" aria-label="Rechercher">
      <button class="btn" type="submit">Chercher</button>
    </form>

    @if (!p) {
      <p class="loading">Chargement…</p>
    } @else if (p.families) {
      <div class="cards">
        @for (family of p.families; track family.id) {
          <a class="card" routerLink="/materiel" [queryParams]="{ cat: family.id }">
            <span class="card-title">{{ family.name }}</span>
            <span class="card-foot">{{ family.count | plural: 'objet' }}</span>
          </a>
        } @empty {
          <p class="muted">Aucun matériel disponible pour le moment.</p>
        }
      </div>
    } @else {
      @if (p.subcategories?.length && !q()) {
        <div class="chips">
          @for (sub of p.subcategories; track sub.id) {
            <a class="chip" routerLink="/materiel" [queryParams]="{ cat: sub.id }">{{ sub.name }} <small>{{ sub.count }}</small></a>
          }
        </div>
      }
      @if (q()) {
        <p>
          {{ p.resultCount | plural: 'résultat' }} pour « {{ q() }} »@if (p.category) { dans {{ p.category.name }} }
          · <a routerLink="/materiel" [queryParams]="{ cat: cat() }">Effacer</a>
        </p>
      }
      @for (group of p.groups; track group.heading) {
        <h2 class="group-title">{{ group.heading }}</h2>
        <ul class="list">
          @for (item of group.items; track item.id) {
            <app-item-row [item]="item" [admin]="auth.isAdmin()">
              @if (auth.isAdmin()) {
                <a class="round ghost" [routerLink]="['/gestion/materiel', item.id]" [attr.aria-label]="'Modifier ' + item.name" title="Modifier">✎</a>
              }
              @if (item.addable) {
                <button class="round" type="button" (click)="add(item)" [disabled]="adding() === item.id"
                        [attr.aria-label]="'Ajouter ' + item.name + ' au panier'" title="Ajouter au panier">+</button>
              }
            </app-item-row>
          }
        </ul>
      } @empty {
        <p class="muted">Aucun objet ne correspond.</p>
      }
    }
  `,
})
export class ItemList {
  private api = inject(Api);
  private router = inject(Router);
  private messages = inject(Messages);
  protected auth = inject(Auth);

  /** Paramètres de l'URL (?cat=…&q=…). */
  cat = input<string>();
  q = input<string>();

  page = signal<ListPage | null>(null);
  adding = signal<number | null>(null);
  query = '';

  constructor() {
    effect(() => {
      const cat = this.cat();
      const q = this.q();
      this.query = q ?? '';
      this.page.set(null);
      this.api.get<ListPage>('/materiel', { cat, q }).subscribe({
        next: (page) => this.page.set(page),
        error: (e) => {
          this.messages.errors(errorMessages(e));
          this.router.navigateByUrl('/materiel');
        },
      });
    });
  }

  search(): void {
    this.router.navigate(['/materiel'], { queryParams: { cat: this.cat() || null, q: this.query.trim() || null } });
  }

  add(item: Item): void {
    this.adding.set(item.id);
    this.api.post<CartChange>('/panier', { item: item.id, quantity: 1 }).subscribe({
      next: (change) => {
        this.adding.set(null);
        this.auth.setCartCount(change.cartCount);
        // Nouvel objet (pas de mutation) pour que la ligne se redessine
        const inCart = Math.min(item.inCart + 1, item.available);
        const updated = { ...item, inCart, addable: Math.max(item.available - inCart, 0) };
        this.page.update((p) => p && {
          ...p,
          groups: p.groups?.map((g) => ({ ...g, items: g.items.map((i) => (i.id === item.id ? updated : i)) })),
        });
        if (change.limited) {
          this.messages.warning(change.message!);
        } else {
          this.messages.success(change.message!);
        }
      },
      error: (e) => {
        this.adding.set(null);
        this.messages.errors(errorMessages(e));
      },
    });
  }
}
