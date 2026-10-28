package scout.materiel.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scout.materiel.Fixtures;
import scout.materiel.accounts.PasswordHasher;
import scout.materiel.accounts.User;
import scout.materiel.inventory.Item;
import scout.materiel.loans.LineStatus;
import scout.materiel.loans.LoanRequest;

/** Reprise d'une base au schéma de l'application Django (tables auth_user, inventory_*, loans_*). */
class DjangoImportTest {
    @TempDir
    Path dir;

    Path djangoDatabase() throws Exception {
        Path file = dir.resolve("django.sqlite3");
        String hash = new PasswordHasher(1000).hash("mot-de-passe-du-chef");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE auth_user (id INTEGER PRIMARY KEY, password TEXT, last_login TEXT, is_superuser INTEGER,"
                    + " username TEXT, last_name TEXT, email TEXT, is_staff INTEGER, is_active INTEGER, date_joined TEXT, first_name TEXT)");
            st.execute("CREATE TABLE inventory_category (id INTEGER PRIMARY KEY, name TEXT, is_consumable INTEGER, parent_id INTEGER)");
            st.execute("CREATE TABLE inventory_item (id INTEGER PRIMARY KEY, name TEXT, reference TEXT, quantity INTEGER, location TEXT,"
                    + " comment TEXT, remark TEXT, is_consumable INTEGER, is_active INTEGER, created_at TEXT, updated_at TEXT, category_id INTEGER)");
            st.execute("CREATE TABLE loans_request (id INTEGER PRIMARY KEY, created_at TEXT, updated_at TEXT, requester_id INTEGER)");
            st.execute("CREATE TABLE loans_requestline (id INTEGER PRIMARY KEY, quantity INTEGER, status TEXT, admin_comment TEXT,"
                    + " reserved_at TEXT, returned_at TEXT, item_id INTEGER, request_id INTEGER)");
            st.execute("INSERT INTO auth_user VALUES (1, '" + hash + "', '2026-01-02 10:00:00.123456', 1, 'chef', 'Dupont', 'chef@example.org', 1, 1,"
                    + " '2025-09-01 08:00:00', 'Jeanne')");
            st.execute("INSERT INTO auth_user VALUES (7, '!nonutilisable', NULL, 0, 'louveteau', '', 'louveteau@example.org', 0, 1,"
                    + " '2025-09-02 08:00:00', '')");
            st.execute("INSERT INTO inventory_category VALUES (3, 'Cuisine', 0, NULL), (4, 'Ustensiles', 0, 3)");
            st.execute("INSERT INTO inventory_item VALUES (12, 'Économes', NULL, 5, 'A.1', '', '', 0, 1, '2025-09-01 08:00:00',"
                    + " '2025-09-01 08:00:00', 4)");
            st.execute("INSERT INTO loans_request VALUES (20, '2026-02-01 09:00:00', '2026-02-01 09:00:00', 7)");
            st.execute("INSERT INTO loans_requestline VALUES (30, 2, 'reservee', 'à rendre lundi', '2026-02-02 09:00:00', NULL, 12, 20)");
        }
        return file;
    }

    @Test
    void copiesEverythingAndKeepsPasswords() throws Exception {
        Fixtures f = new Fixtures();
        String report = DjangoImport.run(f.services.db, djangoDatabase());
        assertTrue(report.contains("2 comptes"), report);

        User chef = f.services.accounts.authenticate("chef", "mot-de-passe-du-chef");
        assertTrue(chef.isAdmin());
        assertEquals("Jeanne Dupont", chef.displayName());
        User guest = f.services.accounts.findUser(7).orElseThrow();
        assertFalse(guest.isAdmin());
        assertFalse(guest.hasPassword(), "mot de passe Django inutilisable = invitation à renvoyer");

        LoanRequest request = f.services.loans.findRequest(20).orElseThrow();
        assertEquals(LineStatus.RESERVED, request.lines.get(0).status);
        assertEquals("à rendre lundi", request.lines.get(0).adminComment);
        assertEquals("2026-02-01T09:00:00Z", request.createdAt);
        Item item = f.services.inventory.itemPage(chef, 12, Map.of()).item;
        assertEquals(3, item.available);
        assertEquals("Cuisine › Ustensiles", item.categoryPath);
    }

    @Test
    void refusesANonEmptyDatabase() throws Exception {
        Fixtures f = new Fixtures();
        f.admin();
        Path file = djangoDatabase();
        assertThrows(IllegalStateException.class, () -> DjangoImport.run(f.services.db, file));
        assertTrue(Files.exists(file));
    }

    @Test
    void convertsDjangoDates() {
        assertEquals("2026-01-02T10:00:00.123456Z", DjangoImport.iso("2026-01-02 10:00:00.123456"));
        assertEquals("2026-01-02T10:00:00Z", DjangoImport.iso("2026-01-02 10:00:00+00:00"));
        assertEquals(null, DjangoImport.iso(null));
    }
}
