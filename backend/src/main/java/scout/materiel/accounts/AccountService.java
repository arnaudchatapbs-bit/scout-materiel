package scout.materiel.accounts;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import scout.materiel.Config;
import scout.materiel.common.ApiError;
import scout.materiel.common.Text;
import scout.materiel.db.Database;
import scout.materiel.mail.Mailer;

/** Connexion, sessions, mots de passe, invitations et gestion des comptes. */
public class AccountService {
    public static final Duration TOKEN_VALIDITY = Duration.ofDays(7);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern USERNAME = Pattern.compile("^[\\w.@+-]{1,150}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Database db;
    private final Config config;
    private final Mailer mailer;
    private final PasswordHasher hasher;
    /** Échecs de connexion récents par identifiant (protection contre les essais en série). */
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AccountService(Database db, Config config, Mailer mailer) {
        this.db = db;
        this.config = config;
        this.mailer = mailer;
        this.hasher = new PasswordHasher(config.passwordIterations);
    }

    public PasswordHasher hasher() {
        return hasher;
    }

    // Connexion ----------------------------------------------------------------------

    /** Connexion par identifiant ou e-mail, sans tenir compte de la casse. */
    public User authenticate(String login, String password) {
        String key = Text.normalize(login);
        Failures recent = failures.get(key);
        if (recent != null && recent.locked()) {
            throw new ApiError(429, "Trop d'essais : réessayez dans quelques minutes.");
        }
        List<User> candidates = db.read(tx -> tx.list(
                "SELECT " + User.COLUMNS + " FROM users u WHERE u.username = ? OR u.email = ?",
                User::map, Text.orEmpty(login), Text.orEmpty(login)));
        for (User user : candidates) {
            if (user.active && user.hasPassword() && hasher.verify(password, user.passwordHash)) {
                failures.remove(key);
                String now = Instant.now().toString();
                String rehashed = hasher.needsRehash(user.passwordHash) ? hasher.hash(password) : user.passwordHash;
                db.tx(tx -> tx.update("UPDATE users SET last_login = ?, password_hash = ? WHERE id = ?", now, rehashed, user.id));
                user.lastLogin = now;
                user.passwordHash = rehashed;
                return user;
            }
        }
        if (candidates.isEmpty()) {
            hasher.dummyHash(password);
        }
        failures.computeIfAbsent(key, k -> new Failures()).add();
        throw ApiError.invalid("Identifiant ou mot de passe incorrect.");
    }

    public String createSession(long userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String expires = Instant.now().plus(Duration.ofDays(config.sessionDays)).toString();
        db.tx(tx -> {
            tx.update("DELETE FROM sessions WHERE expires_at < ?", Instant.now().toString());
            return tx.update("INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)", sha256(token), userId, expires);
        });
        return token;
    }

    /** Utilisateur actif de la session, s'il y en a une valide. */
    public Optional<User> sessionUser(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        return db.read(tx -> tx.one(
                "SELECT " + User.COLUMNS + " FROM sessions s JOIN users u ON u.id = s.user_id"
                        + " WHERE s.token_hash = ? AND s.expires_at > ? AND u.is_active = 1",
                User::map, sha256(token), Instant.now().toString()));
    }

    public void deleteSession(String token) {
        if (token != null) {
            db.tx(tx -> tx.update("DELETE FROM sessions WHERE token_hash = ?", sha256(token)));
        }
    }

    // Mots de passe ---------------------------------------------------------------------

    public void changePassword(User user, String current, String password) {
        if (!hasher.verify(current, user.passwordHash)) {
            throw ApiError.invalid("Le mot de passe actuel est incorrect.");
        }
        setPassword(user, password);
    }

    private void setPassword(User user, String password) {
        String error = PasswordHasher.validate(password, user);
        if (error != null) {
            throw ApiError.invalid(error);
        }
        String hash = hasher.hash(password);
        db.tx(tx -> {
            // Changer de mot de passe ferme les autres sessions
            tx.update("DELETE FROM sessions WHERE user_id = ?", user.id);
            return tx.update("UPDATE users SET password_hash = ? WHERE id = ?", hash, user.id);
        });
        user.passwordHash = hash;
    }

    /**
     * Jeton pour choisir son mot de passe (invitation ou « mot de passe oublié »), valable 7 jours.
     * Il est signé avec le hash actuel du mot de passe : il ne sert qu'une fois.
     */
    public String passwordToken(User user) {
        long expires = Instant.now().plus(TOKEN_VALIDITY).getEpochSecond();
        String payload = user.id + "." + Long.toString(expires, 36);
        return payload + "." + sign(payload + "|" + user.passwordHash + "|" + user.email);
    }

    public Optional<User> userFromToken(String token) {
        String[] parts = token == null ? new String[0] : token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long userId;
        long expires;
        try {
            userId = Long.parseLong(parts[0]);
            expires = Long.parseLong(parts[1], 36);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (Instant.now().getEpochSecond() > expires) {
            return Optional.empty();
        }
        Optional<User> user = findUser(userId).filter(u -> u.active);
        return user.filter(u -> MessageDigest.isEqual(
                sign(parts[0] + "." + parts[1] + "|" + u.passwordHash + "|" + u.email).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8)));
    }

    public User resetPassword(String token, String password) {
        User user = userFromToken(token).orElseThrow(() -> ApiError.invalid(
                "Ce lien n'est plus valable (déjà utilisé ou expiré). Demandez-en un nouveau."));
        setPassword(user, password);
        return user;
    }

    /** « Mot de passe oublié » : ne dit jamais si l'adresse existe. */
    public void requestPasswordReset(String email) {
        db.read(tx -> tx.one("SELECT " + User.COLUMNS + " FROM users u WHERE u.email = ? AND u.is_active = 1",
                User::map, Text.orEmpty(email))).ifPresent(user -> mailer.send(
                "Réinitialisation de votre mot de passe — Scout Matériel",
                "Bonjour " + firstNameOrUsername(user) + ",\n\n"
                        + "Vous avez demandé à changer votre mot de passe sur Scout Matériel.\n"
                        + "Ouvrez ce lien pour en choisir un nouveau (valable 7 jours, une seule fois) :\n\n"
                        + passwordLink(user) + "\n\n"
                        + "Votre identifiant : " + user.username + "\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez ce message.\n\n— Scout Matériel\n",
                List.of(user.email)));
    }

    /** Envoie l'e-mail d'invitation. Renvoie false si le compte est désactivé ou si le mail n'est pas parti. */
    public boolean sendInvitation(User user) {
        if (!user.active || user.email.isEmpty()) {
            return false;
        }
        return mailer.send("Votre compte Scout Matériel",
                "Bonjour " + firstNameOrUsername(user) + ",\n\n"
                        + "Un compte vient d'être créé pour vous sur Scout Matériel, l'application de gestion du matériel du groupe.\n"
                        + "Vous pourrez y consulter le matériel disponible et faire vos demandes.\n\n"
                        + "Pour choisir votre mot de passe, ouvrez ce lien (valable 7 jours, une seule fois) :\n\n"
                        + passwordLink(user) + "\n\n"
                        + "Votre identifiant : " + user.username + " (vous pouvez aussi vous connecter avec votre adresse e-mail)\n"
                        + "Adresse de l'application : " + config.siteUrl + "/\n\n— Scout Matériel\n",
                List.of(user.email));
    }

    private String passwordLink(User user) {
        return config.siteUrl + "/nouveau-mot-de-passe?jeton=" + passwordToken(user);
    }

    private static String firstNameOrUsername(User user) {
        return user.firstName.isEmpty() ? user.username : user.firstName;
    }

    // Comptes (admin) --------------------------------------------------------------------

    public Optional<User> findUser(long id) {
        return db.read(tx -> tx.one("SELECT " + User.COLUMNS + " FROM users u WHERE u.id = ?", User::map, id));
    }

    public List<User> listUsers() {
        return db.read(tx -> tx.list("SELECT " + User.COLUMNS + " FROM users u ORDER BY u.username COLLATE NOCASE", User::map));
    }

    public long activeGuestCount() {
        return listUsers().stream().filter(u -> u.active && !Roles.isAdmin(u)).count();
    }

    /** Données d'un formulaire de compte. */
    public static class UserForm {
        public String username;
        public String email;
        public String firstName;
        public String lastName;
        public boolean admin;
        public boolean active = true;
    }

    public User createUser(UserForm form) {
        List<String> errors = validate(form, null);
        if (!errors.isEmpty()) {
            throw ApiError.invalid(errors);
        }
        long id = db.tx(tx -> tx.insert(
                "INSERT INTO users (username, email, first_name, last_name, is_admin, is_active, date_joined) VALUES (?, ?, ?, ?, ?, ?, ?)",
                form.username.trim(), form.email.trim(), Text.orEmpty(form.firstName), Text.orEmpty(form.lastName),
                form.admin, form.active, Instant.now().toString()));
        return findUser(id).orElseThrow();
    }

    public User updateUser(long id, UserForm form, User actingUser) {
        User user = findUser(id).orElseThrow(() -> ApiError.notFound("Compte introuvable."));
        List<String> errors = validate(form, user);
        if (user.id == actingUser.id && (!form.active || !form.admin)) {
            errors.add("Vous ne pouvez pas retirer vos propres droits ni désactiver votre compte.");
        }
        if (!errors.isEmpty()) {
            throw ApiError.invalid(errors);
        }
        db.tx(tx -> {
            if (!form.active) {
                tx.update("DELETE FROM sessions WHERE user_id = ?", id);
            }
            return tx.update("UPDATE users SET username = ?, email = ?, first_name = ?, last_name = ?, is_admin = ?, is_active = ? WHERE id = ?",
                    form.username.trim(), form.email.trim(), Text.orEmpty(form.firstName), Text.orEmpty(form.lastName),
                    form.admin, form.active, id);
        });
        return findUser(id).orElseThrow();
    }

    private List<String> validate(UserForm form, User existing) {
        List<String> errors = new ArrayList<>();
        String username = Text.orEmpty(form.username);
        String email = Text.orEmpty(form.email);
        if (!USERNAME.matcher(username).matches()) {
            errors.add("Identifiant obligatoire : lettres, chiffres et . @ + - _ uniquement.");
        }
        if (!EMAIL.matcher(email).matches()) {
            errors.add("Adresse e-mail obligatoire et valide.");
        }
        long excluded = existing == null ? 0 : existing.id;
        if (errors.isEmpty()) {
            if (db.read(tx -> tx.count("SELECT COUNT(*) FROM users WHERE username = ? AND id <> ?", username, excluded)) > 0) {
                errors.add("Cet identifiant est déjà utilisé.");
            }
            if (db.read(tx -> tx.count("SELECT COUNT(*) FROM users WHERE email = ? AND id <> ?", email, excluded)) > 0) {
                errors.add("Cette adresse e-mail est déjà utilisée par un autre compte.");
            }
        }
        return errors;
    }

    /** Commande creer-admin : crée (ou promeut) un administrateur avec un mot de passe. */
    public User createAdmin(String username, String email, String password) {
        UserForm form = new UserForm();
        form.username = username;
        form.email = email;
        form.admin = true;
        User user = createUser(form);
        setPassword(user, password);
        return user;
    }

    // Outils ------------------------------------------------------------------------------

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signature = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature).substring(0, 32);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Failures {
        private int count;
        private Instant last = Instant.now();

        synchronized void add() {
            if (Instant.now().isAfter(last.plus(LOCKOUT))) {
                count = 0;
            }
            count++;
            last = Instant.now();
        }

        synchronized boolean locked() {
            return count >= MAX_FAILURES && Instant.now().isBefore(last.plus(LOCKOUT));
        }
    }
}
