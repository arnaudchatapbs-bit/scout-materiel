import { Pipe, PipeTransform } from '@angular/core';
import { LineStatus, RequestStatus } from './models';

/** « 3 disponibles », « 1 disponible ». */
export function plural(n: number, word: string): string {
  return `${n} ${word}${n > 1 ? 's' : ''}`;
}

@Pipe({ name: 'plural' })
export class PluralPipe implements PipeTransform {
  transform(n: number | null | undefined, word: string): string {
    return plural(n ?? 0, word);
  }
}

/** Date ISO → « 28/10/2026 » ou « 28/10/2026 à 14:05 ». */
@Pipe({ name: 'frDate' })
export class FrDatePipe implements PipeTransform {
  transform(iso: string | null | undefined, withTime = false): string {
    if (!iso) {
      return '';
    }
    const date = new Date(iso);
    const day = date.toLocaleDateString('fr-FR');
    return withTime ? `${day} à ${date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })}` : day;
  }
}

export const LINE_STATUS: Record<LineStatus, { label: string; badge: string }> = {
  en_attente: { label: 'En attente', badge: 'badge-info' },
  reservee: { label: 'Réservé', badge: 'badge-ok' },
  refusee: { label: 'Refusé', badge: 'badge-danger' },
  annulee: { label: 'Annulé', badge: 'badge-muted' },
  rendue: { label: 'Rendu', badge: 'badge-muted' },
};

export const REQUEST_STATUS: Record<RequestStatus, { label: string; badge: string }> = {
  en_attente: { label: 'En attente', badge: 'badge-info' },
  reservee: { label: 'Réservée', badge: 'badge-ok' },
  terminee: { label: 'Terminée', badge: 'badge-muted' },
  annulee: { label: 'Annulée', badge: 'badge-muted' },
};
