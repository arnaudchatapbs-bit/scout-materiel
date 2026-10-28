import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { errorMessages } from '../../core/api';
import { Auth } from '../../core/auth';
import { ImportReport } from '../../core/models';
import { Errors } from '../../shared/ui';

@Component({
  selector: 'app-import',
  imports: [FormsModule, RouterLink, Errors],
  template: `
    <nav class="crumbs"><a routerLink="/gestion">Gestion</a></nav>
    <div class="page-head">
      <div class="titles">
        <h1>Import Excel</h1>
        <p class="subtitle">Nomenclature du matériel (.xlsx, onglet « Nomenclature »). Relançable : les objets existants sont mis à jour, pas dupliqués.</p>
      </div>
    </div>

    <app-errors [errors]="errors()" />

    <form class="panel" (ngSubmit)="submit()">
      <label class="field">
        <span>Fichier</span>
        <input type="file" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" (change)="pick($event)" required>
      </label>
      <label class="field">
        <span>Onglet</span>
        <input type="text" name="sheet" [(ngModel)]="sheet">
      </label>
      <label class="check">
        <input type="checkbox" name="dryRun" [(ngModel)]="dryRun">
        <span>Simulation : afficher le résultat sans rien enregistrer</span>
      </label>
      <div class="panel-actions">
        <button class="btn" type="submit" [disabled]="!file || busy()">{{ busy() ? 'Import en cours…' : (dryRun ? 'Simuler' : 'Importer') }}</button>
      </div>
    </form>

    @if (report(); as r) {
      <div class="panel">
        <h2>{{ r.dryRun ? 'Simulation (rien n\\'a été enregistré)' : 'Import terminé' }}</h2>
        <div class="chips">
          <span class="chip">Catégories créées <small>{{ r.categoriesCreated }}</small></span>
          <span class="chip">Objets créés <small>{{ r.created }}</small></span>
          <span class="chip">Objets mis à jour <small>{{ r.updated }}</small></span>
          <span class="chip">Lignes ignorées <small>{{ r.skipped }}</small></span>
        </div>
        @if (r.doubtful.length) {
          <h3 class="section-title">Doublons signalés à vérifier</h3>
          <ul class="list">@for (line of r.doubtful; track line) { <li class="row"><span class="row-main">{{ line }}</span></li> }</ul>
        }
        @if (r.merged.length) {
          <h3 class="section-title">Lignes fusionnées</h3>
          <ul class="list">@for (line of r.merged; track line) { <li class="row"><span class="row-main">{{ line }}</span></li> }</ul>
        }
        @if (r.warnings.length) {
          <h3 class="section-title">Avertissements</h3>
          <ul class="list">@for (line of r.warnings; track line) { <li class="row"><span class="row-main">{{ line }}</span></li> }</ul>
        }
        @if (!r.dryRun) {
          <div class="panel-actions"><a class="btn secondary" routerLink="/gestion/materiel">Voir le matériel</a></div>
        }
      </div>
    }
  `,
})
export class Import {
  private http = inject(HttpClient);
  private auth = inject(Auth);
  file: File | null = null;
  sheet = 'Nomenclature';
  dryRun = true;
  report = signal<ImportReport | null>(null);
  errors = signal<string[]>([]);
  busy = signal(false);

  pick(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  submit(): void {
    if (!this.file) {
      return;
    }
    const data = new FormData();
    data.append('fichier', this.file);
    data.append('onglet', this.sheet);
    data.append('simulation', String(this.dryRun));
    this.busy.set(true);
    this.errors.set([]);
    this.report.set(null);
    this.http.post<ImportReport>('/api/gestion/import', data).subscribe({
      next: (report) => {
        this.busy.set(false);
        this.report.set(report);
        this.auth.reload();
      },
      error: (e) => {
        this.busy.set(false);
        this.errors.set(errorMessages(e));
      },
    });
  }
}
