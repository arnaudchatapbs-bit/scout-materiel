import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { Api, errorMessages } from '../../core/api';
import { PluralPipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { CategoryRow, Item } from '../../core/models';
import { Availability, Errors } from '../../shared/ui';

interface Form {
  name: string;
  categoryId: number | null;
  reference: string;
  quantity: number;
  location: string;
  comment: string;
  remark: string;
  isConsumable: boolean;
  active: boolean;
}

@Component({
  selector: 'app-item-form',
  imports: [FormsModule, RouterLink, Errors, Availability, PluralPipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a><span class="sep">›</span><a routerLink="/gestion/materiel">Matériel</a></nav>
    @if (loaded()) {
      <div class="page-head">
        <div class="titles">
          <h1>{{ isNew() ? 'Nouvel objet' : form.name }}</h1>
          @if (item(); as it) {
            <p class="subtitle"><app-availability [item]="it" [admin]="true" /></p>
          }
        </div>
        @if (item(); as it) {
          <div class="actions"><a class="btn ghost small" [routerLink]="['/materiel', it.id]">Voir la fiche</a></div>
        }
      </div>

      <app-errors [errors]="errors()" />

      <form class="panel" (ngSubmit)="save()">
        <label class="field">
          <span>Nom</span>
          <input type="text" name="name" [(ngModel)]="form.name" required maxlength="200">
        </label>
        <label class="field">
          <span>Catégorie</span>
          <select name="categoryId" [(ngModel)]="form.categoryId" required>
            <option [ngValue]="null" disabled>— Choisir —</option>
            @for (c of categories(); track c.id) {
              <option [ngValue]="c.id">{{ c.path }}</option>
            }
          </select>
        </label>
        <div class="grid-2">
          <label class="field">
            <span>Référence</span>
            <input type="text" name="reference" [(ngModel)]="form.reference" maxlength="50" class="ref">
            <small class="help">Facultative, unique si renseignée.</small>
          </label>
          <label class="field">
            <span>Quantité totale</span>
            <input type="number" name="quantity" [(ngModel)]="form.quantity" min="0" inputmode="numeric">
            @if (item(); as it) {
              @if (it.reserved) { <small class="help">{{ it.reserved | plural: 'exemplaire' }} actuellement réservé{{ it.reserved > 1 ? 's' : '' }}.</small> }
            }
          </label>
        </div>
        <label class="field">
          <span>Localisation</span>
          <input type="text" name="location" [(ngModel)]="form.location" maxlength="100" placeholder="ex. A.3">
        </label>
        <label class="field">
          <span>Commentaire</span>
          <textarea name="comment" [(ngModel)]="form.comment"></textarea>
        </label>
        <label class="field">
          <span>Remarque d'import</span>
          <textarea name="remark" [(ngModel)]="form.remark"></textarea>
          <small class="help">Visible seulement par l'administrateur.</small>
        </label>
        <label class="check">
          <input type="checkbox" name="isConsumable" [(ngModel)]="form.isConsumable">
          <span>Consommable (visible mais non réservable)
            @if (inheritedConsumable()) { <small class="muted"> — déjà consommable par sa catégorie</small> }
          </span>
        </label>
        <label class="check">
          <input type="checkbox" name="active" [(ngModel)]="form.active">
          <span>Actif (un objet inactif n'apparaît plus dans l'inventaire)</span>
        </label>

        <div class="panel-actions">
          <button class="btn" type="submit" [disabled]="busy()">Enregistrer</button>
          <a class="btn ghost" routerLink="/gestion/materiel">Annuler</a>
          <span class="spacer"></span>
          @if (!isNew()) {
            <button class="btn danger" type="button" (click)="remove()" [disabled]="busy()">Supprimer</button>
          }
        </div>
      </form>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class ItemForm implements OnInit {
  private api = inject(Api);
  private router = inject(Router);
  private messages = inject(Messages);
  id = input.required<string>();
  /** Catégorie proposée pour un nouvel objet (?cat=…). */
  cat = input<string>();

  isNew = computed(() => this.id() === 'nouveau');
  item = signal<Item | null>(null);
  categories = signal<CategoryRow[]>([]);
  loaded = signal(false);
  errors = signal<string[]>([]);
  busy = signal(false);
  form: Form = {
    name: '', categoryId: null, reference: '', quantity: 1, location: '', comment: '', remark: '',
    isConsumable: false, active: true,
  };

  inheritedConsumable(): boolean {
    const category = this.categories().find((c) => c.id === this.form.categoryId);
    return !!category && (category.consumable || category.inheritedConsumable);
  }

  ngOnInit(): void {
    forkJoin({
      categories: this.api.get<CategoryRow[]>('/gestion/categories'),
      item: this.isNew() ? of(null) : this.api.get<Item>(`/gestion/materiel/${this.id()}`),
    }).subscribe({
      next: ({ categories, item }) => {
        this.categories.set(categories);
        if (item) {
          this.item.set(item);
          this.form = {
            name: item.name, categoryId: item.categoryId, reference: item.reference ?? '', quantity: item.quantity,
            location: item.location, comment: item.comment, remark: item.remark ?? '',
            isConsumable: item.isConsumable, active: item.active,
          };
        } else if (this.cat()) {
          this.form.categoryId = Number(this.cat());
        }
        this.loaded.set(true);
      },
      error: (e) => {
        this.messages.errors(errorMessages(e));
        this.router.navigateByUrl('/gestion/materiel');
      },
    });
  }

  save(): void {
    this.busy.set(true);
    this.errors.set([]);
    const request = this.isNew()
      ? this.api.post<Item>('/gestion/materiel', this.form)
      : this.api.put<Item>(`/gestion/materiel/${this.id()}`, this.form);
    request.subscribe({
      next: (item) => {
        this.messages.success(`« ${item.name} » enregistré.`);
        this.router.navigate(['/gestion/materiel'], { queryParams: { cat: null } });
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }

  remove(): void {
    if (!confirm(`Supprimer définitivement « ${this.form.name} » ?`)) {
      return;
    }
    this.busy.set(true);
    this.api.delete(`/gestion/materiel/${this.id()}`).subscribe({
      next: () => {
        this.messages.success('Objet supprimé.');
        this.router.navigateByUrl('/gestion/materiel');
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }
}
