package scout.materiel.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import scout.materiel.db.Database;

/**
 * Reprise des données de l'application Django (db.sqlite3) : comptes, catégories, objets, demandes.
 * Les identifiants sont conservés, et les mots de passe restent valides (même format PBKDF2).
 * Uniquement dans une base vide, pour ne rien écraser.
 */
public final class DjangoImport {
    /** Format des dates enregistrées par Django dans SQLite (UTC) : « 2025-01-31 18:04:05.123456 ». */
    private static final DateTimeFormatter DJANGO = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.MICRO_OF_SECOND, 0, 6, true).optionalEnd()
            .toFormatter();

    private DjangoImport() {
    }

    public static String run(Database db, Path djangoDb) {
        if (!Files.isRegularFile(djangoDb)) {
            throw new IllegalArgumentException("Fichier introuvable : " + djangoDb);
        }
        // ATTACH est interdit dans une transaction : on attache avant, on détache après
        return db.withConnection(connection -> {
            try (PreparedStatement st = connection.prepareStatement("ATTACH DATABASE ? AS old")) {
                st.setString(1, djangoDb.toAbsolutePath().toString());
                st.execute();
            }
            try {
                return copy(db);
            } finally {
                try (Statement st = connection.createStatement()) {
                    st.execute("DETACH DATABASE old");
                }
            }
        });
    }

    private static String copy(Database db) {
        return db.tx(tx -> {
            if (tx.count("SELECT COUNT(*) FROM users") + tx.count("SELECT COUNT(*) FROM items") > 0) {
                throw new IllegalStateException("La base n'est pas vide : la reprise ne se fait que dans une base neuve.");
            }
            List<Object[]> users = tx.list("SELECT id, username, email, first_name, last_name, password, is_staff, is_superuser,"
                    + " is_active, date_joined, last_login FROM old.auth_user", rs -> new Object[]{
                    rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    // Mot de passe inutilisable chez Django (« !… ») = pas encore choisi
                    rs.getString(6).startsWith("!") ? "" : rs.getString(6),
                    rs.getInt(7) == 1 && rs.getInt(8) == 1, rs.getInt(9) == 1, iso(rs.getString(10)), iso(rs.getString(11))});
            for (Object[] u : users) {
                tx.update("INSERT INTO users (id, username, email, first_name, last_name, password_hash, is_admin, is_active,"
                        + " date_joined, last_login) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", u);
            }
            int categories = tx.update("INSERT INTO categories (id, name, parent_id, is_consumable)"
                    + " SELECT id, name, parent_id, is_consumable FROM old.inventory_category");
            List<Object[]> items = tx.list("SELECT id, name, category_id, reference, quantity, location, comment, remark,"
                    + " is_consumable, is_active, created_at, updated_at FROM old.inventory_item", rs -> new Object[]{
                    rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getString(6),
                    rs.getString(7), rs.getString(8), rs.getInt(9), rs.getInt(10), iso(rs.getString(11)), iso(rs.getString(12))});
            for (Object[] i : items) {
                tx.update("INSERT INTO items (id, name, category_id, reference, quantity, location, comment, remark, is_consumable,"
                        + " is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", i);
            }
            List<Object[]> requests = tx.list("SELECT id, requester_id, created_at, updated_at FROM old.loans_request",
                    rs -> new Object[]{rs.getLong(1), rs.getLong(2), iso(rs.getString(3)), iso(rs.getString(4))});
            for (Object[] r : requests) {
                tx.update("INSERT INTO requests (id, requester_id, created_at, updated_at) VALUES (?, ?, ?, ?)", r);
            }
            List<Object[]> lines = tx.list("SELECT id, request_id, item_id, quantity, status, admin_comment, reserved_at, returned_at"
                    + " FROM old.loans_requestline", rs -> new Object[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4),
                    rs.getString(5), rs.getString(6), iso(rs.getString(7)), iso(rs.getString(8))});
            for (Object[] l : lines) {
                tx.update("INSERT INTO request_lines (id, request_id, item_id, quantity, status, admin_comment, reserved_at, returned_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", l);
            }
            return "Reprise terminée : " + users.size() + " comptes, " + categories + " catégories, " + items.size()
                    + " objets, " + requests.size() + " demandes (" + lines.size() + " lignes).";
        });
    }

    static String iso(String django) {
        if (django == null || django.isEmpty()) {
            return null;
        }
        String value = django.replace('T', ' ');
        int offset = Math.max(value.indexOf('+', 10), value.lastIndexOf('Z'));
        if (offset > 0) {
            value = value.substring(0, offset);
        }
        return LocalDateTime.parse(value.trim(), DJANGO).toInstant(ZoneOffset.UTC).toString();
    }
}
