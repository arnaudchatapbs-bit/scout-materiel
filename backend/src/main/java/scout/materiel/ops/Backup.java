package scout.materiel.ops;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import scout.materiel.Services;

/**
 * Sauvegarde à chaud de la base (VACUUM INTO : copie cohérente sans arrêter l'application),
 * compressée en db-AAAAMMJJ-HHMMSS.sqlite3.gz, avec rotation et envoi par e-mail en option.
 */
public final class Backup {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private Backup() {
    }

    public static Path run(Services services, Path folder, int keep, boolean email, StringBuilder log) throws Exception {
        Files.createDirectories(folder);
        Path destination = folder.resolve("db-" + LocalDateTime.now().format(STAMP) + ".sqlite3.gz");
        Path raw = Files.createTempFile(folder, "backup-", ".sqlite3");
        Files.delete(raw);
        try {
            services.db.withConnection(connection -> {
                try (Statement st = connection.createStatement()) {
                    st.execute("VACUUM INTO '" + raw.toAbsolutePath().toString().replace("'", "''") + "'");
                }
                return null;
            });
            try (InputStream in = Files.newInputStream(raw); OutputStream out = new GZIPOutputStream(Files.newOutputStream(destination))) {
                in.transferTo(out);
            }
        } finally {
            Files.deleteIfExists(raw);
        }
        log.append("Sauvegarde écrite : ").append(destination).append(" (").append(Files.size(destination) / 1024).append(" Ko)\n");

        int removed = rotate(folder, keep);
        if (removed > 0) {
            log.append(removed).append(" ancienne(s) sauvegarde(s) supprimée(s).\n");
        }
        if (email) {
            List<String> recipients = services.notifications.adminRecipients();
            if (recipients.isEmpty()) {
                throw new IllegalStateException("Aucun destinataire : renseignez ADMIN_EMAILS ou l'e-mail d'un compte admin.");
            }
            services.mailer.deliverWithAttachment(
                    "Scout Matériel — sauvegarde du " + LocalDateTime.now().format(DAY),
                    "Sauvegarde automatique de la base Scout Matériel, en pièce jointe.\n"
                            + "Conservez ce mail : il permet de tout restaurer si la carte SD du Raspberry Pi tombe en panne\n"
                            + "(voir DEPLOIEMENT.md, section « Restaurer une sauvegarde »).",
                    recipients, destination);
            log.append("Sauvegarde envoyée à ").append(String.join(", ", recipients)).append(".\n");
        }
        return destination;
    }

    static int rotate(Path folder, int keep) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "db-*.sqlite3.gz")) {
            files.forEach(backups::add);
        }
        Collections.sort(backups, Collections.reverseOrder());
        int removed = 0;
        for (int i = Math.max(keep, 1); i < backups.size(); i++) {
            Files.delete(backups.get(i));
            removed++;
        }
        return removed;
    }

    /**
     * Remplace la base par une sauvegarde (.sqlite3 ou .sqlite3.gz). L'application doit être arrêtée.
     * L'ancienne base est d'abord copiée à côté ; renvoie le chemin de cette copie.
     */
    public static Path restore(Path source, Path database) throws IOException, SQLException {
        Path raw = Files.createTempFile("restore-", ".sqlite3");
        try {
            try (InputStream in = source.toString().endsWith(".gz")
                    ? new GZIPInputStream(Files.newInputStream(source)) : Files.newInputStream(source)) {
                Files.copy(in, raw, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            check(raw);
            Path safety = database.resolveSibling(database.getFileName() + ".avant-restauration-" + LocalDateTime.now().format(STAMP));
            if (Files.exists(database)) {
                try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                     Statement st = connection.createStatement()) {
                    // Intègre le journal WAL avant de copier
                    st.execute("VACUUM INTO '" + safety.toAbsolutePath().toString().replace("'", "''") + "'");
                }
            }
            for (String suffix : new String[]{"-wal", "-shm"}) {
                Files.deleteIfExists(database.resolveSibling(database.getFileName() + suffix));
            }
            Files.copy(raw, database, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return safety;
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    private static void check(Path file) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement st = connection.createStatement()) {
            String integrity = st.executeQuery("PRAGMA integrity_check").getString(1);
            if (!"ok".equals(integrity)) {
                throw new IllegalStateException("Sauvegarde corrompue : " + integrity);
            }
            if (!st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'items'").next()
                    || st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'items'").getInt(1) == 0) {
                throw new IllegalStateException("Ce fichier n'est pas une sauvegarde de Scout Matériel (Java).");
            }
        }
    }
}
