import { Component, OnInit, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api } from '../../core/api';
import { PluralPipe } from '../../core/format';
import { CategoryRow, Item } from '../../core/models';
import { ItemRow } from '../../shared/ui';

const STATES = [
  { key: 'actifs', label: 'Actifs' },
  { key: 'inactifs', label: 'Inactifs' },
  { key: 'tous', label: 'Tous' },
];

/** Tout le matériel, y compris inactif : mêmes lignes que la page Matériel, ouvertes sur le formulaire. */
@Component({
  selector: 'app-manage-items',
  imports: [FormsModule, RouterLink, ItemRow, PluralPipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head">
      <div class="titles"><h1>Matériel</h1></div>
      <div class="actions"><a class="btn small" routerLink="/gestion/materiel/nouveau" [queryParams]="{ cat: cat() }">+ Nouvel objet</a></div>
    </div>

    <form class="search" role="search" (ngSubmit)="go({ q: query.trim() || null })">
      <input type="search" name="q" [(ngModel)]="query" placeholder="Nom, référence, lieu…" aria-label="Rechercher">
      <button class="btn" type="submit">Chercher</button>
    </form>

    <div class="chips">
      @for (s of states; track s.key) {
        <a class="chip" [class.active]="state() === s.key" routerLink="/gestion/materiel" queryParamsHandling="merge"
           [queryParams]="{ etat: s.key }">{{ s.label }}</a>
      }
    </div>
    <div class="chips">
      <a class="chip" [class.active]="!cat()" routerLink="/gestion/materiel" queryParamsHandling="merge" [queryParams]="{ cat: null }">Toutes les familles</a>
      @for (f of families(); track f.id) {
        <a class="chip" [class.active]="cat() === '' + f.id" routerLink="/gestion/materiel" queryParamsHandling="merge"
           [queryParams]="{ cat: f.id }">{{ f.name }} <small>{{ f.itemCount }}</small></a>
      }
    </div>

    @if (items(); as list) {
      <p class="muted small">{{ list.length | plural: 'objet' }}</p>
      <ul class="list">
        @for (item of list; track item.id) {
          <app-item-row [item]="item" [admin]="true" [showPath]="true" [showInCart]="false" [href]="'/gestion/materiel/' + item.id">
            <a class="round ghost" [routerLink]="['/gestion/materiel', item.id]" [attr.aria-label]="'Modifier ' + item.name" title="Modifier">✎</a>
          </app-item-row>
        } @empty {
          <li class="empty">Aucun objet.</li>
        }
      </ul>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class ManageItems implements OnInit {
  private api = inject(Api);
  private router = inject(Router);
  cat = input<string>();
  q = input<string>();
  etat = input<string>();

  states = STATES;
  state = signal('actifs');
  families = signal<CategoryRow[]>([]);
  items = signal<Item[] | null>(null);
  query = '';

  constructor() {
    effect(() => {
      const state = STATES.some((s) => s.key === this.etat()) ? this.etat()! : 'actifs';
      this.state.set(state);
      this.query = this.q() ?? '';
      this.items.set(null);
      this.api.get<Item[]>('/gestion/materiel', { cat: this.cat(), q: this.q(), etat: state })
        .subscribe((items) => this.items.set(items));
    });
  }

  ngOnInit(): void {
    this.api.get<CategoryRow[]>('/gestion/categories').subscribe((rows) => this.families.set(rows.filter((r) => r.depth === 0)));
  }

  go(params: Record<string, string | null>): void {
    this.router.navigate(['/gestion/materiel'], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
