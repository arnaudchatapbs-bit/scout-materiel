import { Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { FrDatePipe } from '../../core/format';
import { Messages } from '../../core/messages';
import { Account } from '../../core/models';
import { Errors } from '../../shared/ui';

@Component({
  selector: 'app-account-form',
  imports: [FormsModule, RouterLink, Errors, FrDatePipe],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a><span class="sep">›</span><a routerLink="/gestion/comptes">Comptes</a></nav>
    @if (loaded()) {
      <div class="page-head">
        <div class="titles">
          <h1>{{ isNew() ? 'Nouveau compte' : account()?.displayName }}</h1>
          @if (account(); as a) {
            <p class="subtitle">
              Créé le {{ a.dateJoined | frDate }} · {{ a.lastLogin ? 'dernière connexion le ' + (a.lastLogin | frDate) : 'jamais connecté' }}
              @if (!a.hasPassword) { · <span class="badge badge-warn">Invitation en attente</span> }
            </p>
          }
        </div>
        @if (account(); as a) {
          @if (a.active) {
            <div class="actions">
              <button class="btn secondary small" type="button" (click)="invite()" [disabled]="busy()">
                ✉️ {{ a.hasPassword ? 'Renvoyer un lien de mot de passe' : "Renvoyer l'invitation" }}
              </button>
            </div>
          }
        }
      </div>

      <app-errors [errors]="errors()" />

      <form class="panel" (ngSubmit)="save()">
        <div class="grid-2">
          <label class="field">
            <span>Identifiant</span>
            <input type="text" name="username" [(ngModel)]="form.username" required autocapitalize="none" maxlength="150">
          </label>
          <label class="field">
            <span>Adresse e-mail</span>
            <input type="email" name="email" [(ngModel)]="form.email" required>
          </label>
          <label class="field">
            <span>Prénom</span>
            <input type="text" name="firstName" [(ngModel)]="form.firstName">
          </label>
          <label class="field">
            <span>Nom</span>
            <input type="text" name="lastName" [(ngModel)]="form.lastName">
          </label>
        </div>
        <label class="check">
          <input type="checkbox" name="admin" [(ngModel)]="form.admin" [disabled]="self()">
          <span>Administrateur (tous les droits)</span>
        </label>
        <label class="check">
          <input type="checkbox" name="active" [(ngModel)]="form.active" [disabled]="self()">
          <span>Compte actif (un compte désactivé ne peut plus se connecter)</span>
        </label>
        @if (isNew()) {
          <label class="check">
            <input type="checkbox" name="sendInvitation" [(ngModel)]="form.sendInvitation">
            <span>Envoyer l'invitation par e-mail (lien pour choisir le mot de passe, valable 7 jours)</span>
          </label>
        }
        @if (self()) { <p class="muted small">Vous ne pouvez pas retirer vos propres droits ni désactiver votre compte.</p> }
        <div class="panel-actions">
          <button class="btn" type="submit" [disabled]="busy()">{{ isNew() ? 'Créer le compte' : 'Enregistrer' }}</button>
          <a class="btn ghost" routerLink="/gestion/comptes">Annuler</a>
        </div>
      </form>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class AccountForm implements OnInit {
  private api = inject(Api);
  private auth = inject(Auth);
  private router = inject(Router);
  private messages = inject(Messages);
  id = input.required<string>();

  isNew = computed(() => this.id() === 'nouveau');
  self = computed(() => this.account()?.id === this.auth.me()?.id);
  account = signal<Account | null>(null);
  loaded = signal(false);
  errors = signal<string[]>([]);
  busy = signal(false);
  form = { username: '', email: '', firstName: '', lastName: '', admin: false, active: true, sendInvitation: true };

  ngOnInit(): void {
    if (this.isNew()) {
      this.loaded.set(true);
      return;
    }
    this.api.get<Account>(`/gestion/comptes/${this.id()}`).subscribe({
      next: (a) => {
        this.account.set(a);
        this.form = { username: a.username, email: a.email, firstName: a.firstName, lastName: a.lastName,
          admin: a.admin, active: a.active, sendInvitation: false };
        this.loaded.set(true);
      },
      error: (e) => {
        this.messages.errors(errorMessages(e));
        this.router.navigateByUrl('/gestion/comptes');
      },
    });
  }

  save(): void {
    this.busy.set(true);
    this.errors.set([]);
    if (this.isNew()) {
      this.api.post<{ account: Account; invited: boolean }>('/gestion/comptes', this.form).subscribe({
        next: ({ account, invited }) => {
          this.messages.success(`Compte « ${account.username} » créé.`);
          if (this.form.sendInvitation) {
            if (invited) {
              this.messages.success(`Invitation envoyée à ${account.email}.`);
            } else {
              this.messages.warning("Le compte est créé, mais l'e-mail d'invitation n'a pas pu partir.");
            }
          }
          this.auth.reload();
          this.router.navigateByUrl('/gestion/comptes');
        },
        error: (e) => this.fail(e),
      });
    } else {
      this.api.put<Account>(`/gestion/comptes/${this.id()}`, this.form).subscribe({
        next: (account) => {
          this.messages.success(`Compte « ${account.username} » enregistré.`);
          this.auth.reload();
          this.router.navigateByUrl('/gestion/comptes');
        },
        error: (e) => this.fail(e),
      });
    }
  }

  invite(): void {
    this.busy.set(true);
    this.api.post<{ invited: boolean }>(`/gestion/comptes/${this.id()}/invitation`).subscribe({
      next: ({ invited }) => {
        this.busy.set(false);
        if (invited) {
          this.messages.success(`Lien envoyé à ${this.account()!.email}.`);
        } else {
          this.messages.warning("L'e-mail n'a pas pu partir : vérifiez la configuration SMTP.");
        }
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(e: unknown): void {
    this.busy.set(false);
    this.errors.set(errorMessages(e));
  }
}
