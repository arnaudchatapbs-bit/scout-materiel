import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, errorMessages } from '../../core/api';
import { Messages } from '../../core/messages';
import { NotificationSettings } from '../../core/models';
import { Errors } from '../../shared/ui';

const SOURCES: Record<NotificationSettings['source'], string> = {
  reglage: 'adresses saisies ci-dessus',
  env: 'adresses du fichier de configuration du serveur (ADMIN_EMAILS)',
  comptes: 'e-mails des comptes administrateurs',
};

@Component({
  selector: 'app-settings',
  imports: [FormsModule, RouterLink, Errors],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head">
      <div class="titles">
        <h1>Réglages</h1>
        <p class="subtitle">Adresses qui reçoivent les listes de matériel demandées.</p>
      </div>
    </div>

    <app-errors [errors]="errors()" />

    @if (settings(); as s) {
      <form class="panel" (ngSubmit)="save()">
        <label class="field">
          <span>Adresses e-mail des destinataires</span>
          <textarea name="emails" [(ngModel)]="emails" rows="2" autocapitalize="none" spellcheck="false"
                    placeholder="materiel@exemple.fr, chef@exemple.fr"></textarea>
          <small class="help">
            Plusieurs adresses possibles, séparées par une virgule. Elles reçoivent chaque nouvelle demande (avec la liste
            du matériel et son emplacement), les annulations et la sauvegarde du dimanche. Laisser vide pour revenir au
            comportement par défaut.
          </small>
        </label>
        <div class="panel-actions">
          <button class="btn" type="submit" [disabled]="busy()">Enregistrer</button>
        </div>
      </form>

      <div class="panel">
        <h2>Destinataires actuels</h2>
        @if (s.recipients.length) {
          <div class="chips">@for (email of s.recipients; track email) { <span class="chip">{{ email }}</span> }</div>
          <p class="muted small">Origine : {{ source(s) }}.</p>
        } @else {
          <p><span class="badge badge-warn">Aucun destinataire</span> Les demandes ne seront envoyées à personne.</p>
        }
      </div>
    } @else {
      <p class="loading">Chargement…</p>
    }
  `,
})
export class Settings implements OnInit {
  private api = inject(Api);
  private messages = inject(Messages);
  settings = signal<NotificationSettings | null>(null);
  errors = signal<string[]>([]);
  busy = signal(false);
  emails = '';

  ngOnInit(): void {
    this.api.get<NotificationSettings>('/gestion/reglages').subscribe({
      next: (s) => this.show(s),
      error: (e) => this.errors.set(errorMessages(e)),
    });
  }

  save(): void {
    this.busy.set(true);
    this.errors.set([]);
    this.api.put<NotificationSettings>('/gestion/reglages', { emails: this.emails }).subscribe({
      next: (s) => {
        this.busy.set(false);
        this.show(s);
        this.messages.success(s.source === 'reglage' ? 'Destinataires enregistrés.' : 'Adresses effacées : retour au comportement par défaut.');
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }

  protected source(s: NotificationSettings): string {
    return SOURCES[s.source];
  }

  private show(s: NotificationSettings): void {
    this.settings.set(s);
    this.emails = s.emails;
  }
}
