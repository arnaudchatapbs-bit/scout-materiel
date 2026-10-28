package scout.materiel.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;
import org.sqlite.SQLiteConfig;

/**
 * Base SQLite : une seule connexion partagée, protégée par un verrou.
 *
 * Sur le Pi 1 (un seul cœur), un pool n'apporte rien : SQLite sérialise de toute façon les
 * écritures. read() pour les lectures, tx() pour une transaction tout-ou-rien.
 */
public final class Database implements AutoCloseable {
    /** Migrations dans l'ordre ; PRAGMA user_version = nombre de migrations appliquées. */
    private static final String[] MIGRATIONS = {"/db/001_initial.sql"};

    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    private Database(Connection connection) {
        this.connection = connection;
    }

    public static Database open(String path) {
        try {
            if (!path.equals(":memory:")) {
                Path parent = Path.of(path).toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            }
            SQLiteConfig config = new SQLiteConfig();
            // WAL + synchronous=NORMAL : moins d'écritures sur la carte SD
            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.enforceForeignKeys(true);
            config.setBusyTimeout(5000);
            config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
            Database db = new Database(connection);
            db.migrate();
            return db;
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Ouverture de la base " + path + " impossible", e);
        }
    }

    public static Database inMemory() {
        return open(":memory:");
    }

    private void migrate() throws SQLException, IOException {
        int version;
        try (Statement st = connection.createStatement()) {
            version = st.executeQuery("PRAGMA user_version").getInt(1);
        }
        for (int i = version; i < MIGRATIONS.length; i++) {
            String sql;
            try (InputStream in = Database.class.getResourceAsStream(MIGRATIONS[i])) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            final int next = i + 1;
            tx(t -> {
                for (String statement : sql.split(";\\s*\\n")) {
                    if (!statement.replaceAll("(?m)^--.*$", "").isBlank()) {
                        t.update(statement);
                    }
                }
                t.update("PRAGMA user_version = " + next);
                return null;
            });
        }
    }

    /** Lecture (sans transaction explicite). */
    public <T> T read(SqlWork<T> work) {
        lock.lock();
        try {
            return work.run(new Tx(connection));
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Transaction : tout est annulé si work lève une exception. */
    public <T> T tx(SqlWork<T> work) {
        lock.lock();
        try {
            boolean autoCommit = connection.getAutoCommit();
            if (!autoCommit) {
                // Transaction déjà ouverte (appel imbriqué) : on s'y joint
                return work.run(new Tx(connection));
            }
            // Le pilote ouvre la transaction en mode IMMEDIATE (voir open()) : verrou d'écriture pris
            // tout de suite, pas d'échec « database is locked » en cours de route
            connection.setAutoCommit(false);
            try {
                T result = work.run(new Tx(connection));
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            lock.unlock();
        }
    }

    /** Accès direct, pour la sauvegarde (le verrou garantit qu'aucune requête ne tourne). */
    public <T> T withConnection(SqlFunction<Connection, T> work) {
        lock.lock();
        try {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new DbException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Fermeture : rien à faire de plus
        }
    }

    @FunctionalInterface
    public interface SqlWork<T> {
        T run(Tx tx) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<A, T> {
        T apply(A value) throws SQLException;
    }

    public static final class DbException extends RuntimeException {
        public DbException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
