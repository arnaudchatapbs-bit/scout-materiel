package scout.materiel;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import scout.materiel.accounts.User;
import scout.materiel.db.Database;

/** Base en mémoire, mails en mémoire, et petites fabriques de données de test. */
public final class Fixtures {
    public static final String PASSWORD = "un-mot-de-passe-solide";

    public final Services services;

    public Fixtures() {
        this(new HashMap<>());
    }

    public Fixtures(Map<String, String> extra) {
        Map<String, String> env = new HashMap<>();
        env.put("DEBUG", "true");
        env.put("EMAIL_BACKEND", "memory");
        // Tests rapides : très peu d'itérations
        env.put("PASSWORD_ITERATIONS", "1000");
        env.put("SITE_URL", "http://test.local");
        env.putAll(extra);
        services = new Services(Config.of(env), Database.inMemory());
    }

    public User user(String username, boolean admin) {
        long id = services.db.tx(tx -> tx.insert(
                "INSERT INTO users (username, email, first_name, password_hash, is_admin, date_joined) VALUES (?, ?, ?, ?, ?, ?)",
                username, username + "@example.org", "", services.accounts.hasher().hash(PASSWORD), admin, Instant.now().toString()));
        return services.accounts.findUser(id).orElseThrow();
    }

    public User admin() {
        return user("chef", true);
    }

    public User guest() {
        return user("invite", false);
    }

    public long category(String name, Long parentId, boolean consumable) {
        return services.db.tx(tx -> tx.insert("INSERT INTO categories (name, parent_id, is_consumable) VALUES (?, ?, ?)",
                name, parentId, consumable));
    }

    public long item(String name, long categoryId, int quantity) {
        String now = Instant.now().toString();
        return services.db.tx(tx -> tx.insert(
                "INSERT INTO items (name, category_id, quantity, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                name, categoryId, quantity, now, now));
    }

    public void setLineStatus(long requestId, String status) {
        services.db.tx(tx -> tx.update("UPDATE request_lines SET status = ? WHERE request_id = ?", status, requestId));
    }
}
