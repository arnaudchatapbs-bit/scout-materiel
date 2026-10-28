import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { FrDatePipe, PluralPipe } from '../core/format';
import { LoanRequest } from '../core/models';
import { RequestStatusBadge } from './ui';

/** Ligne de demande dans une liste (« Mes demandes » et gestion : même rendu). */
@Component({
  selector: 'app-request-row',
  imports: [RouterLink, RequestStatusBadge, FrDatePipe, PluralPipe],
  template: `
    @let r = request();
    <li class="row">
      <a class="row-main" [routerLink]="link()">
        <span class="row-title">Demande n°{{ r.id }}@if (showRequester()) { · {{ r.requesterName }} }</span>
        <span class="row-meta">
          <span>{{ r.createdAt | frDate: true }}</span>
          <span>{{ r.lines.length | plural: 'article' }}</span>
          @if (pending()) { <span>{{ pending() }} en attente</span> }
        </span>
        <span class="row-meta">{{ summary() }}</span>
      </a>
      <span class="row-status"><app-request-status [status]="r.status" /></span>
    </li>
  `,
  host: { style: 'display: contents' },
})
export class RequestRow {
  request = input.required<LoanRequest>();
  showRequester = input(false);
  /** Préfixe du lien : /demandes (invité) ou /gestion/demandes (admin). */
  base = input('/demandes');
  link = computed(() => `${this.base()}/${this.request().id}`);
  pending = computed(() => this.request().lines.filter((l) => l.status === 'en_attente').length);
  summary = computed(() => {
    const names = this.request().lines.map((l) => `${l.itemName} × ${l.quantity}`);
    return names.length > 3 ? names.slice(0, 3).join(', ') + '…' : names.join(', ');
  });
}
