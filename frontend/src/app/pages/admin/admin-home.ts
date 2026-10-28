import { Component, OnInit, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Auth } from '../../core/auth';
import { PluralPipe } from '../../core/format';

@Component({
  selector: 'app-admin-home',
  imports: [RouterLink, PluralPipe],
  template: `
    @if (auth.me(); as me) {
      <div class="page-head">
        <div class="titles">
          <h1>Gestion</h1>
          <p class="subtitle">Demandes, matériel, catégories et comptes du groupe.</p>
        </div>
      </div>
      <div class="cards">
        <a routerLink="/gestion/demandes" class="card" [class.highlight]="me.pendingRequests">
          <span class="card-icon">📋</span>
          <span class="card-title">Demandes</span>
          <span class="card-text">Réserver ou refuser chaque article, marquer les retours.</span>
          <span class="card-foot">{{ me.pendingRequests ? (me.pendingRequests | plural: 'demande') + ' à traiter' : 'Rien en attente' }}</span>
        </a>
        <a routerLink="/gestion/materiel" class="card">
          <span class="card-icon">📦</span>
          <span class="card-title">Matériel</span>
          <span class="card-text">Ajouter, modifier ou désactiver des objets, y compris les inactifs.</span>
          <span class="card-foot">{{ me.itemCount | plural: 'objet actif' }}</span>
        </a>
        <a routerLink="/gestion/categories" class="card">
          <span class="card-icon">🗂️</span>
          <span class="card-title">Catégories</span>
          <span class="card-text">Organiser l'arborescence et marquer les consommables.</span>
        </a>
        <a routerLink="/gestion/comptes" class="card">
          <span class="card-icon">👥</span>
          <span class="card-title">Comptes</span>
          <span class="card-text">Créer les comptes invités et envoyer les invitations.</span>
          <span class="card-foot">{{ (me.guestCount ?? 0) | plural: 'invité actif' }}</span>
        </a>
        <a routerLink="/gestion/import" class="card">
          <span class="card-icon">📥</span>
          <span class="card-title">Import Excel</span>
          <span class="card-text">Mettre à jour l'inventaire depuis la nomenclature (.xlsx).</span>
        </a>
      </div>
    }
  `,
})
export class AdminHome implements OnInit {
  protected auth = inject(Auth);

  ngOnInit(): void {
    this.auth.reload();
  }
}
