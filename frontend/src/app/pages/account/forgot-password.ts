import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Errors } from '../../shared/ui';

@Component({
  selector: 'app-forgot-password',
  imports: [FormsModule, RouterLink, Errors],
  template: `
    <div class="narrow" style="margin-top: 8vh">
      <div class="page-head"><div class="titles"><h1>Mot de passe oublié</h1></div></div>
      @if (sent()) {
        <div class="panel">
          <p>Si un compte correspond à cette adresse, un e-mail vient de partir avec un lien pour choisir un nouveau mot de passe.</p>
          <p class="muted small">Pensez à regarder dans les indésirables.</p>
          <a routerLink="/connexion" class="btn secondary block">Retour à la connexion</a>
        </div>
      } @else {
        <form class="panel" (ngSubmit)="submit()">
          <app-errors [errors]="errors()" />
          <p class="muted">Indiquez l'adresse e-mail de votre compte : vous recevrez un lien pour choisir un nouveau mot de passe.</p>
          <label class="field">
            <span>Adresse e-mail</span>
            <input type="email" name="email" [(ngModel)]="email" autocomplete="email" required autofocus>
          </label>
          <button class="btn block" type="submit" [disabled]="busy()">Envoyer le lien</button>
          <p style="text-align: center; margin: 14px 0 0"><a routerLink="/connexion">Retour à la connexion</a></p>
        </form>
      }
    </div>
  `,
})
export class ForgotPassword {
  private api = inject(Api);
  email = '';
  sent = signal(false);
  busy = signal(false);
  errors = signal<string[]>([]);

  submit(): void {
    this.busy.set(true);
    this.api.post('/auth/mot-de-passe-oublie', { email: this.email }).subscribe({
      next: () => this.sent.set(true),
      error: (e) => {
        this.errors.set(errorMessages(e));
        this.busy.set(false);
      },
    });
  }
}
