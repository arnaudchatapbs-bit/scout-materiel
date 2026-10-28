import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { Messages } from '../../core/messages';
import { Me } from '../../core/models';
import { Errors } from '../../shared/ui';

@Component({
  selector: 'app-change-password',
  imports: [FormsModule, Errors],
  template: `
    <div class="narrow">
      <div class="page-head"><div class="titles"><h1>Mon mot de passe</h1></div></div>
      <form class="panel" (ngSubmit)="submit()">
        <app-errors [errors]="errors()" />
        <label class="field">
          <span>Mot de passe actuel</span>
          <input type="password" name="current" [(ngModel)]="current" autocomplete="current-password" required>
        </label>
        <label class="field">
          <span>Nouveau mot de passe</span>
          <input type="password" name="password" [(ngModel)]="password" autocomplete="new-password" required minlength="12">
          <small class="help">12 caractères minimum. Vos autres appareils seront déconnectés.</small>
        </label>
        <label class="field">
          <span>Confirmation</span>
          <input type="password" name="confirm" [(ngModel)]="confirm" autocomplete="new-password" required>
        </label>
        <button class="btn block" type="submit" [disabled]="busy()">Changer le mot de passe</button>
      </form>
    </div>
  `,
})
export class ChangePassword {
  private api = inject(Api);
  private auth = inject(Auth);
  private router = inject(Router);
  private messages = inject(Messages);
  current = '';
  password = '';
  confirm = '';
  errors = signal<string[]>([]);
  busy = signal(false);

  submit(): void {
    if (this.password !== this.confirm) {
      this.errors.set(['Les deux mots de passe ne correspondent pas.']);
      return;
    }
    this.busy.set(true);
    this.api.post<Me>('/auth/mot-de-passe', { current: this.current, password: this.password }).subscribe({
      next: (me) => {
        this.auth.setSession(me);
        this.messages.success('Mot de passe changé.');
        this.router.navigateByUrl('/');
      },
      error: (e) => {
        this.errors.set(errorMessages(e));
        this.busy.set(false);
      },
    });
  }
}
