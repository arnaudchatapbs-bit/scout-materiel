package scout.materiel.common;

import scout.materiel.db.Database;

/** Réglages modifiables depuis l'interface (table settings) : ils l'emportent sur le .env. */
public final class Settings {
    /** Destinataires des e-mails « admin » (demandes, annulations, sauvegarde), séparés par des virgules. */
    public static final String NOTIFICATION_EMAILS = "notification_emails";

    private final Database db;

    public Settings(Database db) {
        this.db = db;
    }

    /** Valeur du réglage, chaîne vide s'il n'est pas renseigné. */
    public String get(String key) {
        return db.read(tx -> tx.one("SELECT value FROM settings WHERE key = ?", rs -> rs.getString(1), key)).orElse("");
    }

    /** Une valeur vide supprime le réglage (retour au comportement par défaut). */
    public void set(String key, String value) {
        String text = Text.orEmpty(value);
        db.tx(tx -> text.isEmpty()
                ? tx.update("DELETE FROM settings WHERE key = ?", key)
                : tx.update("INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, text));
    }
}
