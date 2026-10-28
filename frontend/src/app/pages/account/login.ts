import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { Errors } from '../../shared/ui';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, Errors],
  template: `
    <div class="narrow" style="margin-top: 8vh">
      <p style="font-size: 3rem; text-align: center; margin: 0">⛺</p>
      <h1 style="text-align: center; margin-bottom: 20px">Scout Matériel</h1>
      <form class="panel" (ngSubmit)="submit()">
        <app-errors [errors]="errors()" />
        <label class="field">
          <span>Identifiant ou e-mail</span>
          <input type="text" name="login" [(ngModel)]="login" autocomplete="username" autocapitalize="none" required autofocus>
        </label>
        <label class="field">
          <span>Mot de passe</span>
          <input type="password" name="password" [(ngModel)]="password" autocomplete="current-password" required>
        </label>
        <button class="btn block" type="submit" [disabled]="busy()">{{ busy() ? 'Connexion…' : 'Se connecter' }}</button>
        <p style="text-align: center; margin: 14px 0 0"><a routerLink="/mot-de-passe-oublie">Mot de passe oublié ?</a></p>
      </form>
      <p class="muted small" style="text-align: center">Les comptes sont créés par l'administrateur du groupe.</p>
    </div>
  `,
})
export class Login {
  private auth = inject(Auth);
  private router = inject(Router);
  /** Page demandée avant la connexion (?suite=…). */
  suite = input<string>();

  login = '';
  password = '';
  errors = signal<string[]>([]);
  busy = signal(false);

  submit(): void {
    this.busy.set(true);
    this.auth.login(this.login, this.password).subscribe({
      next: () => {
        const target = this.suite();
        // Uniquement un chemin interne (pas de redirection vers un autre site)
        this.router.navigateByUrl(target && target.startsWith('/') && !target.startsWith('//') ? target : '/');
      },
      error: (e) => {
        this.errors.set(errorMessages(e));
        this.busy.set(false);
      },
    });
  }
}
