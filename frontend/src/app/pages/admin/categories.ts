import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { PluralPipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { CategoryRow } from '../../core/models';
import { Errors } from '../../shared/ui';

interface Form {
  id: number | null;
  name: string;
  parentId: number | null;
  consumable: boolean;
}

/** Arborescence en lignes indentées (même liste que le matériel), formulaire dans un panneau. */
@Component({
  selector: 'app-categories',
  imports: [FormsModule, RouterLink, Errors, PluralPipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head">
      <div class="titles">
        <h1>Catégories</h1>
        <p class="subtitle">Jusqu'à 4 niveaux : Famille → Sous-catégorie 1 → 2 → 3.</p>
      </div>
      @if (!form()) {
        <div class="actions"><button class="btn small" type="button" (click)="edit(null)">+ Nouvelle catégorie</button></div>
      }
    </div>

    @if (form(); as f) {
      <form class="panel" (ngSubmit)="save()">
        <h2>{{ f.id ? 'Modifier « ' + original + ' »' : 'Nouvelle catégorie' }}</h2>
        <app-errors [errors]="errors()" />
        <label class="field">
          <span>Nom</span>
          <input type="text" name="name" [(ngModel)]="f.name" required maxlength="120">
        </label>
        <label class="field">
          <span>Rangée dans</span>
          <select name="parentId" [(ngModel)]="f.parentId">
            <option [ngValue]="null">— Aucune (famille) —</option>
            @for (c of rows(); track c.id) {
              @if (c.depth < 3 && c.id !== f.id) { <option [ngValue]="c.id">{{ c.path }}</option> }
            }
          </select>
        </label>
        <label class="check">
          <input type="checkbox" name="consumable" [(ngModel)]="f.consumable">
          <span>Consommable : ses objets (et ceux des sous-catégories) ne sont pas réservables</span>
        </label>
        <div class="panel-actions">
          <button class="btn" type="submit" [disabled]="busy()">Enregistrer</button>
          <button class="btn ghost" type="button" (click)="form.set(null)">Annuler</button>
          <span class="spacer"></span>
          @if (f.id) { <button class="btn danger" type="button" (click)="remove(f.id)" [disabled]="busy()">Supprimer</button> }
        </div>
      </form>
    }

    @if (rows(); as list) {
      <ul class="list">
        @for (c of list; track c.id) {
          <li class="row" [class]="'indent-' + c.depth">
            <a class="row-main" routerLink="/gestion/materiel" [queryParams]="{ cat: c.id }">
              <span class="row-title">{{ c.depth ? '└ ' : '' }}{{ c.name }}</span>
              <span class="row-meta">{{ c.itemCount | plural: 'objet' }}@if (c.childCount) { · {{ c.childCount | plural: 'sous-catégorie' }} }</span>
            </a>
            <span class="row-status">
              @if (c.consumable) { <span class="badge badge-muted">Consommable</span> }
              @else if (c.inheritedConsumable) { <span class="badge badge-muted">Consommable (hérité)</span> }
            </span>
            <button class="round ghost" type="button" (click)="edit(c)" [attr.aria-label]="'Modifier ' + c.name" title="Modifier">✎</button>
          </li>
        } @empty {
          <li class="empty">Aucune catégorie : importez la nomenclature Excel ou créez une famille.</li>
        }
      </ul>
    }
  `,
})
export class Categories implements OnInit {
  private api = inject(Api);
  private messages = inject(Messages);
  rows = signal<CategoryRow[] | null>(null);
  form = signal<Form | null>(null);
  errors = signal<string[]>([]);
  busy = signal(false);
  original = '';

  ngOnInit(): void {
    this.api.get<CategoryRow[]>('/gestion/categories').subscribe((rows) => this.rows.set(rows));
  }

  edit(row: CategoryRow | null): void {
    this.errors.set([]);
    this.original = row?.name ?? '';
    this.form.set(row
      ? { id: row.id, name: row.name, parentId: row.parentId ?? null, consumable: row.consumable }
      : { id: null, name: '', parentId: null, consumable: false });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  save(): void {
    const f = this.form()!;
    const body = { name: f.name, parentId: f.parentId, consumable: f.consumable };
    this.run(f.id ? this.api.put<CategoryRow[]>(`/gestion/categories/${f.id}`, body) : this.api.post<CategoryRow[]>('/gestion/categories', body),
      `Catégorie « ${f.name} » enregistrée.`);
  }

  remove(id: number): void {
    if (confirm('Supprimer cette catégorie ? Elle doit être vide.')) {
      this.run(this.api.delete<CategoryRow[]>(`/gestion/categories/${id}`), 'Catégorie supprimée.');
    }
  }

  private run(request: ReturnType<Api['get']>, success: string): void {
    this.busy.set(true);
    this.errors.set([]);
    request.subscribe({
      next: (rows) => {
        this.busy.set(false);
        this.rows.set(rows as CategoryRow[]);
        this.form.set(null);
        this.messages.success(success);
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }
}
