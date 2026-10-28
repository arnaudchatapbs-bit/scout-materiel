package scout.materiel;

import io.javalin.Javalin;
import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scout.materiel.accounts.PasswordHasher;
import scout.materiel.inventory.NomenclatureImporter;
import scout.materiel.ops.Backup;
import scout.materiel.ops.DjangoImport;
import scout.materiel.web.WebApp;

/**
 * Point d'entrée : sans argument, démarre le serveur ; sinon exécute une commande.
 * Sur le Pi : ./scout-materiel.sh <commande> (voir DEPLOIEMENT.md).
 */
public final class App {
    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private static final String USAGE = String.join("\n",
            "Usage : java -jar scout-materiel.jar [commande]",
            "",
            "  (aucune)                               démarre le serveur",
            "  creer-admin <identifiant> <e-mail>     crée un compte administrateur (mot de passe demandé)",
            "  import-nomenclature <fichier.xlsx> [--dry-run] [--onglet Nomenclature]",
            "  reprise-django <db.sqlite3>            reprend les données de l'application Django (base vide)",
            "  sauvegarder [--keep 30] [--email] [--dir chemin]",
            "  restaurer <fichier.sqlite3.gz> [--oui] (application arrêtée)",
            "  test-email <adresse>                   vérifie l'envoi SMTP",
            "  calibrer-hachage                       mesure le coût du hachage des mots de passe sur cette machine");

    private App() {
    }

    public static void main(String[] args) throws Exception {
        List<String> argv = new ArrayList<>(Arrays.asList(args));
        String command = argv.isEmpty() ? "serveur" : argv.remove(0);
        if (command.equals("calibrer-hachage")) {
            calibrate();
            return;
        }
        Config config = Config.load();
        switch (command) {
            case "serveur":
                serve(config);
                return;
            case "creer-admin":
                withServices(config, s -> createAdmin(s, argv));
                return;
            case "import-nomenclature":
                withServices(config, s -> importNomenclature(s, argv));
                return;
            case "reprise-django":
                withServices(config, s -> System.out.println(DjangoImport.run(s.db, Path.of(arg(argv, 0, "<db.sqlite3>")))));
                return;
            case "sauvegarder":
                withServices(config, s -> {
                    StringBuilder log = new StringBuilder();
                    Backup.run(s, Path.of(option(argv, "--dir", config.backupDir)), Integer.parseInt(option(argv, "--keep", "30")),
                            argv.contains("--email"), log);
                    System.out.print(log);
                });
                return;
            case "restaurer":
                restore(config, argv);
                return;
            case "test-email":
                withServices(config, s -> {
                    s.mailer.deliver("Scout Matériel — test d'envoi", "Si vous lisez ce message, l'envoi des e-mails fonctionne.",
                            List.of(arg(argv, 0, "<adresse>")));
                    System.out.println("E-mail envoyé (backend : " + config.emailBackend + ").");
                });
                return;
            default:
                System.out.println(USAGE);
                System.exit(command.equals("aide") || command.equals("--help") ? 0 : 2);
        }
    }

    // Serveur -------------------------------------------------------------------------------

    private static void serve(Config config) {
        long start = System.nanoTime();
        Services services = Services.open(config);
        Javalin app = WebApp.create(services).start(config.host, config.port);
        ScheduledExecutorService scheduler = scheduleBackups(services);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            services.close();
        }));
        LOG.info("Scout Matériel démarré sur http://{}:{} en {} ms", config.host, config.port,
                Duration.ofNanos(System.nanoTime() - start).toMillis());
    }

    /**
     * Sauvegarde quotidienne dans l'application (BACKUP_TIME, défaut 03:30 ; vide = désactivée),
     * envoyée par e-mail le dimanche. Évite de lancer une seconde JVM depuis cron sur le Pi 1.
     */
    private static ScheduledExecutorService scheduleBackups(Services services) {
        String time = System.getenv().getOrDefault("BACKUP_TIME", Config.readDotEnv(Path.of(".env")).getOrDefault("BACKUP_TIME", "03:30")).trim();
        if (time.isEmpty()) {
            return null;
        }
        LocalTime at = LocalTime.parse(time);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sauvegarde");
            thread.setDaemon(true);
            return thread;
        });
        LocalDateTime next = LocalDate.now().atTime(at);
        if (!next.isAfter(LocalDateTime.now())) {
            next = next.plusDays(1);
        }
        long delay = Duration.between(LocalDateTime.now(), next).toMinutes();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                StringBuilder log = new StringBuilder();
                boolean sunday = LocalDate.now().getDayOfWeek() == DayOfWeek.SUNDAY;
                Backup.run(services, Path.of(services.config.backupDir), 30, sunday, log);
                LOG.info("{}", log.toString().trim());
            } catch (Exception e) {
                LOG.error("Échec de la sauvegarde automatique", e);
            }
        }, delay, TimeUnit.DAYS.toMinutes(1), TimeUnit.MINUTES);
        LOG.info("Sauvegarde automatique chaque jour à {} (envoi par e-mail le dimanche)", at);
        return scheduler;
    }

    // Commandes ---------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Command {
        void run(Services services) throws Exception;
    }

    private static void withServices(Config config, Command command) {
        try (Services services = Services.open(config)) {
            command.run(services);
        } catch (IllegalArgumentException | IllegalStateException | scout.materiel.common.ApiError e) {
            System.err.println("Erreur : " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Erreur : " + e);
            System.exit(1);
        }
    }

    private static void createAdmin(Services services, List<String> argv) throws Exception {
        String username = arg(argv, 0, "<identifiant>");
        String email = arg(argv, 1, "<e-mail>");
        String password = System.getenv("ADMIN_PASSWORD");
        if (password == null || password.isEmpty()) {
            password = readPassword("Mot de passe (" + PasswordHasher.MIN_LENGTH + " caractères minimum) : ");
            if (!password.equals(readPassword("Confirmation : "))) {
                throw new IllegalArgumentException("Les deux mots de passe ne correspondent pas.");
            }
        }
        services.accounts.createAdmin(username, email, password);
        System.out.println("Administrateur « " + username + " » créé.");
    }

    private static void importNomenclature(Services services, List<String> argv) throws Exception {
        Path file = Path.of(arg(argv, 0, "<fichier.xlsx>"));
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Fichier introuvable : " + file);
        }
        try (InputStream in = Files.newInputStream(file)) {
            NomenclatureImporter.Report report = new NomenclatureImporter(services.db)
                    .run(in, option(argv, "--onglet", "Nomenclature"), argv.contains("--dry-run"));
            System.out.println(report.toText());
        }
    }

    private static void restore(Config config, List<String> argv) throws Exception {
        Path source = Path.of(arg(argv, 0, "<fichier.sqlite3.gz>"));
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Fichier introuvable : " + source);
        }
        if (!argv.contains("--oui")) {
            System.out.print("La base actuelle va être remplacée (l'application doit être arrêtée). Continuer ? [oui/non] ");
            String answer = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if (answer == null || !List.of("o", "oui", "y", "yes").contains(answer.trim().toLowerCase())) {
                System.out.println("Restauration annulée.");
                return;
            }
        }
        Path safety = Backup.restore(source, Path.of(config.databasePath));
        System.out.println("Base restaurée depuis " + source + ".");
        System.out.println("L'ancienne base a été sauvegardée dans " + safety + ".");
        System.out.println("Redémarrez l'application : sudo systemctl start scout-materiel");
    }

    /** Cible : ~0,5 s par connexion sur la machine qui exécute la commande. */
    private static void calibrate() {
        int probe = 20_000;
        PasswordHasher hasher = new PasswordHasher(probe);
        hasher.hash("echauffement-du-jit");
        long start = System.nanoTime();
        hasher.hash("mesure-du-temps-de-hachage");
        double seconds = (System.nanoTime() - start) / 1e9;
        int iterations = (int) Math.max(10_000, Math.round(probe * 0.5 / seconds / 1000) * 1000);
        System.out.printf("%d itérations : %.2f s sur cette machine.%n", probe, seconds);
        System.out.println("Valeur conseillée (≈ 0,5 s par connexion) : PASSWORD_ITERATIONS=" + iterations);
    }

    // Outils ------------------------------------------------------------------------------------

    private static String arg(List<String> argv, int index, String name) {
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < argv.size(); i++) {
            if (argv.get(i).startsWith("--")) {
                if (List.of("--keep", "--dir", "--onglet").contains(argv.get(i))) {
                    i++;
                }
            } else {
                positional.add(argv.get(i));
            }
        }
        if (index >= positional.size()) {
            throw new IllegalArgumentException("Argument manquant : " + name + "\n\n" + USAGE);
        }
        return positional.get(index);
    }

    private static String option(List<String> argv, String name, String defaultValue) {
        int index = argv.indexOf(name);
        return index >= 0 && index + 1 < argv.size() ? argv.get(index + 1) : defaultValue;
    }

    private static String readPassword(String prompt) throws Exception {
        Console console = System.console();
        if (console != null) {
            return new String(console.readPassword(prompt));
        }
        System.out.print(prompt);
        String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        return line == null ? "" : line;
    }
}
