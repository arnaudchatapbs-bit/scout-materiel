import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { Messages } from '../../core/messages';
import { Me } from '../../core/models';
import { Errors } from '../../shared/ui';

/** Lien d'invitation ou de « mot de passe oublié » : choisir son mot de passe, puis connexion automatique. */
@Component({
  selector: 'app-reset-password',
  imports: [FormsModule, RouterLink, Errors],
  template: `
    <div class="narrow" style="margin-top: 8vh">
      @if (invalid()) {
        <div class="page-head"><div class="titles"><h1>Lien expiré</h1></div></div>
        <div class="panel">
          <p>Ce lien n'est plus valable (déjà utilisé ou expiré au bout de 7 jours).</p>
          <a routerLink="/mot-de-passe-oublie" class="btn block">Recevoir un nouveau lien</a>
        </div>
      } @else if (info(); as account) {
        <div class="page-head">
          <div class="titles">
            <h1>{{ account.invitation ? 'Bienvenue' : 'Nouveau mot de passe' }}{{ account.invitation && account.firstName ? ' ' + account.firstName : '' }}</h1>
            <p class="subtitle">Identifiant : <strong>{{ account.username }}</strong></p>
          </div>
        </div>
        <form class="panel" (ngSubmit)="submit()">
          <app-errors [errors]="errors()" />
          <label class="field">
            <span>Mot de passe</span>
            <input type="password" name="password" [(ngModel)]="password" autocomplete="new-password" required minlength="12">
            <small class="help">12 caractères minimum. Une petite phrase est plus facile à retenir.</small>
          </label>
          <label class="field">
            <span>Confirmation</span>
            <input type="password" name="confirm" [(ngModel)]="confirm" autocomplete="new-password" required>
          </label>
          <button class="btn block" type="submit" [disabled]="busy()">Enregistrer et me connecter</button>
        </form>
      } @else {
        <p class="loading">Vérification du lien…</p>
      }
    </div>
  `,
})
export class ResetPassword implements OnInit {
  private api = inject(Api);
  private auth = inject(Auth);
  private router = inject(Router);
  private messages = inject(Messages);
  jeton = input<string>('');

  info = signal<{ username: string; firstName: string; invitation: boolean } | null>(null);
  invalid = signal(false);
  errors = signal<string[]>([]);
  busy = signal(false);
  password = '';
  confirm = '';

  ngOnInit(): void {
    this.api.get<{ username: string; firstName: string; invitation: boolean }>('/auth/jeton', { jeton: this.jeton() })
      .subscribe({ next: (info) => this.info.set(info), error: () => this.invalid.set(true) });
  }

  submit(): void {
    if (this.password !== this.confirm) {
      this.errors.set(['Les deux mots de passe ne correspondent pas.']);
      return;
    }
    this.busy.set(true);
    this.api.post<Me>('/auth/nouveau-mot-de-passe', { token: this.jeton(), password: this.password }).subscribe({
      next: (me) => {
        this.auth.setSession(me);
        this.messages.success('Mot de passe enregistré : vous êtes connecté.');
        this.router.navigateByUrl('/');
      },
      error: (e) => {
        this.errors.set(errorMessages(e));
        this.busy.set(false);
      },
    });
  }
}
