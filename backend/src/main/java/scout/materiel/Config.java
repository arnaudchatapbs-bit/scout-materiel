package scout.materiel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Réglages lus dans l'environnement, complétés par un fichier .env (jamais commité).
 * Les variables d'environnement l'emportent sur le .env.
 */
public final class Config {
    static final String DEV_SECRET_KEY = "dev-insecure-change-me";
    static final String EXAMPLE_SECRET_KEY = "changez-moi-avec-une-longue-chaine-aleatoire";

    public final boolean debug;
    public final String secretKey;
    public final String databasePath;
    public final String backupDir;
    public final String host;
    public final int port;
    /** Adresse publique, pour les liens dans les e-mails (ex. https://scouts-xxx.duckdns.org). */
    public final String siteUrl;
    public final boolean https;
    /** Dossier du front-end compilé, servi par l'application si renseigné (sinon par Caddy). */
    public final String frontendDir;
    public final int passwordIterations;
    public final int sessionDays;

    public final String emailBackend;
    public final String emailHost;
    public final int emailPort;
    public final boolean emailUseTls;
    public final String emailUser;
    public final String emailPassword;
    public final String fromEmail;
    public final List<String> adminEmails;

    private Config(Map<String, String> env) {
        debug = bool(env, "DEBUG", false);
        secretKey = secretKey(env, debug);
        databasePath = env.getOrDefault("DATABASE_PATH", "data/db.sqlite3");
        backupDir = env.getOrDefault("BACKUP_DIR", "data/backups");
        host = env.getOrDefault("HOST", "127.0.0.1");
        port = Integer.parseInt(env.getOrDefault("PORT", "8000"));
        https = bool(env, "HTTPS", false);
        String domain = env.getOrDefault("SITE_DOMAIN", "").trim();
        String url = env.getOrDefault("SITE_URL", "").trim();
        if (url.isEmpty()) {
            url = domain.isEmpty() ? "http://localhost:" + (debug ? "4200" : String.valueOf(port))
                    : (https ? "https://" : "http://") + domain;
        }
        siteUrl = url.replaceAll("/+$", "");
        if (!domain.isEmpty() && https != siteUrl.startsWith("https://")) {
            throw new IllegalStateException("SITE_DOMAIN et HTTPS vont ensemble : vérifiez le .env (voir DEPLOIEMENT.md).");
        }
        frontendDir = env.getOrDefault("FRONTEND_DIR", "").trim();
        String iterations = env.getOrDefault("PASSWORD_ITERATIONS", "").trim();
        passwordIterations = iterations.isEmpty() ? 600_000 : Integer.parseInt(iterations);
        sessionDays = Integer.parseInt(env.getOrDefault("SESSION_DAYS", "90"));

        emailBackend = env.getOrDefault("EMAIL_BACKEND", "console").trim().toLowerCase();
        emailHost = env.getOrDefault("EMAIL_HOST", "localhost");
        emailPort = Integer.parseInt(env.getOrDefault("EMAIL_PORT", "587"));
        emailUseTls = bool(env, "EMAIL_USE_TLS", true);
        emailUser = env.getOrDefault("EMAIL_HOST_USER", "");
        emailPassword = env.getOrDefault("EMAIL_HOST_PASSWORD", "");
        fromEmail = env.getOrDefault("DEFAULT_FROM_EMAIL", "Scout Matériel <noreply@localhost>");
        adminEmails = list(env, "ADMIN_EMAILS");
    }

    public static Config load() {
        Map<String, String> env = new HashMap<>(readDotEnv(Path.of(".env")));
        env.putAll(System.getenv());
        return new Config(env);
    }

    /** Pour les tests : uniquement les valeurs données. */
    public static Config of(Map<String, String> env) {
        return new Config(env);
    }

    static Map<String, String> readDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.isRegularFile(path)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 1) {
                    continue;
                }
                String value = line.substring(eq + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(line.substring(0, eq).trim(), value);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Lecture de " + path + " impossible", e);
        }
        return values;
    }

    static boolean bool(Map<String, String> env, String name, boolean defaultValue) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return List.of("1", "true", "yes", "oui").contains(value.trim().toLowerCase());
    }

    static List<String> list(Map<String, String> env, String name) {
        List<String> values = new ArrayList<>();
        for (String part : env.getOrDefault(name, "").split(",")) {
            if (!part.isBlank()) {
                values.add(part.trim());
            }
        }
        return values;
    }

    /** En production (DEBUG=False), refuse de démarrer sans vraie SECRET_KEY. */
    static String secretKey(Map<String, String> env, boolean debug) {
        String key = env.getOrDefault("SECRET_KEY", "").trim();
        if (debug) {
            return key.isEmpty() ? DEV_SECRET_KEY : key;
        }
        if (key.isEmpty() || key.equals(DEV_SECRET_KEY) || key.equals(EXAMPLE_SECRET_KEY)) {
            throw new IllegalStateException(
                    "SECRET_KEY manquante ou laissée à sa valeur d'exemple : générez-en une (voir DEPLOIEMENT.md).");
        }
        return key;
    }
}
