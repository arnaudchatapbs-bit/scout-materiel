import { Injectable, signal } from '@angular/core';

export type MessageKind = 'success' | 'info' | 'warning' | 'error';

export interface Message {
  id: number;
  kind: MessageKind;
  text: string;
}

/** Messages éphémères affichés en bas de l'écran (équivalent des messages Django). */
@Injectable({ providedIn: 'root' })
export class Messages {
  readonly list = signal<Message[]>([]);
  private next = 1;

  show(kind: MessageKind, text: string): void {
    const message = { id: this.next++, kind, text };
    this.list.update((list) => [...list, message]);
    setTimeout(() => this.dismiss(message.id), kind === 'error' || kind === 'warning' ? 8000 : 4000);
  }

  success(text: string): void { this.show('success', text); }
  info(text: string): void { this.show('info', text); }
  warning(text: string): void { this.show('warning', text); }

  errors(texts: string[]): void {
    texts.forEach((text) => this.show('error', text));
  }

  dismiss(id: number): void {
    this.list.update((list) => list.filter((m) => m.id !== id));
  }
}
