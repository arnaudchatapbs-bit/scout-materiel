import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../core/auth';
import { PluralPipe } from '../core/format';

@Component({
  selector: 'app-home',
  imports: [RouterLink, PluralPipe],
  template: `
    @if (auth.me(); as me) {
      <div class="page-head">
        <div class="titles">
          <h1>Bonjour {{ me.firstName || me.username }}</h1>
          <p class="subtitle">{{ me.admin ? 'Administrateur' : 'Invité' }} · {{ me.itemCount | plural: 'objet' }} dans l'inventaire</p>
        </div>
      </div>

      <div class="cards">
        <a routerLink="/materiel" class="card">
          <span class="card-icon">📦</span>
          <span class="card-title">Matériel</span>
          <span class="card-text">Parcourir l'inventaire et voir ce qui est disponible.</span>
        </a>
        <a routerLink="/panier" class="card" [class.highlight]="me.cartCount">
          <span class="card-icon">🛒</span>
          <span class="card-title">Panier</span>
          <span class="card-text">Vérifier les quantités et envoyer la demande.</span>
          <span class="card-foot">{{ me.cartCount ? (me.cartCount | plural: 'article') : 'Panier vide' }}</span>
        </a>
        <a routerLink="/demandes" class="card">
          <span class="card-icon">📝</span>
          <span class="card-title">Mes demandes</span>
          <span class="card-text">Suivre l'état de chaque article demandé.</span>
        </a>
        @if (me.admin) {
          <a routerLink="/gestion/demandes" class="card" [class.highlight]="me.pendingRequests">
            <span class="card-icon">📋</span>
            <span class="card-title">Demandes à traiter</span>
            <span class="card-text">Réserver ou refuser le matériel demandé, marquer les retours.</span>
            <span class="card-foot">{{ me.pendingRequests ? (me.pendingRequests | plural: 'demande') + ' en attente' : 'Rien en attente' }}</span>
          </a>
          <a routerLink="/gestion" class="card">
            <span class="card-icon">⚙️</span>
            <span class="card-title">Gestion</span>
            <span class="card-text">Matériel, catégories, comptes et import Excel.</span>
            <span class="card-foot">{{ me.guestCount ?? 0 }} compte{{ (me.guestCount ?? 0) > 1 ? 's' : '' }} invité{{ (me.guestCount ?? 0) > 1 ? 's' : '' }} actif{{ (me.guestCount ?? 0) > 1 ? 's' : '' }}</span>
          </a>
        }
        <a routerLink="/compte/mot-de-passe" class="card">
          <span class="card-icon">🔑</span>
          <span class="card-title">Mon mot de passe</span>
          <span class="card-text">Changer votre mot de passe.</span>
        </a>
      </div>
    }
  `,
})
export class Home implements OnInit {
  protected auth = inject(Auth);

  ngOnInit(): void {
    // Compteurs à jour (demandes arrivées entre-temps)
    this.auth.reload();
  }
}
