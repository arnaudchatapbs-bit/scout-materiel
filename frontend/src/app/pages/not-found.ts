import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <div class="narrow" style="text-align: center; margin-top: 10vh">
      <p style="font-size: 3rem; margin: 0">🧭</p>
      <h1>Page introuvable</h1>
      <p class="muted">Cette page n'existe pas, ou l'objet n'est plus disponible.</p>
      <a routerLink="/materiel" class="btn">Voir le matériel</a>
    </div>
  `,
})
export class NotFound {}
