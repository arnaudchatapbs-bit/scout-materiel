import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LINE_STATUS, PluralPipe, REQUEST_STATUS } from '../core/format';
import { CategoryRef, Item, LineStatus, RequestStatus } from '../core/models';

/**
 * Briques communes aux pages invité et admin. Une ligne d'objet a toujours la même forme
 * (nom, référence, localisation, badges) ; seuls les boutons à droite changent (projetés).
 */

/** Badges de disponibilité d'un objet. */
@Component({
  selector: 'app-availability',
  imports: [PluralPipe],
  template: `
    @let it = item();
    @if (!it.active) {
      <span class="badge badge-muted">Inactif</span>
    }
    @if (!it.reservable) {
      <span class="badge badge-muted">Consommable</span>
    } @else if (admin() && it.available === 0) {
      <span class="badge badge-danger">Tout réservé</span>
    } @else if (admin() && it.reserved) {
      <span class="badge badge-warn">{{ it.reserved | plural: 'réservé' }} / {{ it.quantity }}</span>
    } @else {
      <span class="badge badge-ok">{{ it.available | plural: 'disponible' }}</span>
    }
    @if (admin() && it.pending) {
      <span class="badge badge-info">+{{ it.pending }} en attente</span>
    }
  `,
  host: { style: 'display: contents' },
})
export class Availability {
  item = input.required<Item>();
  admin = input(false);
}

/** Ligne d'objet : lien vers la fiche + badges + actions projetées (« + », quantité, « ✎ »…). */
@Component({
  selector: 'app-item-row',
  imports: [RouterLink, Availability],
  template: `
    @let it = item();
    <li class="row" [class.inactive]="!it.active" [id]="'objet-' + it.id">
      <a class="row-main" [routerLink]="link()">
        <span class="row-title">{{ it.name }}</span>
        <span class="row-meta">
          @if (it.reference) { <span class="ref">{{ it.reference }}</span> }
          @if (it.location) { <span>📍 {{ it.location }}</span> }
          @if (showPath()) { <span>{{ it.categoryPath }}</span> }
        </span>
      </a>
      <span class="row-status">
        <app-availability [item]="it" [admin]="admin()" />
        @if (it.inCart && showInCart()) { <span class="in-cart">🛒 {{ it.inCart }} au panier</span> }
      </span>
      <ng-content />
    </li>
  `,
  host: { style: 'display: contents' },
})
export class ItemRow {
  item = input.required<Item>();
  admin = input(false);
  showPath = input(false);
  showInCart = input(true);
  /** Lien de la ligne (fiche publique par défaut, formulaire en gestion). */
  href = input<string | null>(null);
  link = computed(() => this.href() ?? `/materiel/${this.item().id}`);
}

@Component({
  selector: 'app-line-status',
  template: `<span class="badge" [class]="info().badge">{{ info().label }}</span>`,
  host: { style: 'display: contents' },
})
export class LineStatusBadge {
  status = input.required<LineStatus>();
  info = computed(() => LINE_STATUS[this.status()]);
}

@Component({
  selector: 'app-request-status',
  template: `<span class="badge" [class]="info().badge">{{ info().label }}</span>`,
  host: { style: 'display: contents' },
})
export class RequestStatusBadge {
  status = input.required<RequestStatus>();
  info = computed(() => REQUEST_STATUS[this.status()]);
}

/** Fil d'Ariane de l'inventaire (Matériel › Famille › …). */
@Component({
  selector: 'app-breadcrumb',
  imports: [RouterLink],
  template: `
    <nav class="crumbs" aria-label="Fil d'Ariane">
      <a [routerLink]="root()">{{ rootLabel() }}</a>
      @for (crumb of crumbs(); track crumb.id; let last = $last) {
        <span class="sep">›</span>
        @if (last && !linkLast()) {
          <span>{{ crumb.name }}</span>
        } @else {
          <a [routerLink]="root()" [queryParams]="{ cat: crumb.id }">{{ crumb.name }}</a>
        }
      }
    </nav>
  `,
})
export class Breadcrumb {
  crumbs = input<CategoryRef[]>([]);
  linkLast = input(false);
  root = input('/materiel');
  rootLabel = input('Matériel');
}

/** Liste d'erreurs renvoyées par l'API (au-dessus d'un formulaire). */
@Component({
  selector: 'app-errors',
  template: `
    @if (errors().length === 1) {
      <div class="alert error" role="alert">{{ errors()[0] }}</div>
    } @else if (errors().length) {
      <div class="alert error" role="alert"><ul>@for (e of errors(); track e) { <li>{{ e }}</li> }</ul></div>
    }
  `,
})
export class Errors {
  errors = input<string[]>([]);
}
