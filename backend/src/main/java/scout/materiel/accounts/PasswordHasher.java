package scout.materiel.accounts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2-SHA256 au format Django (« pbkdf2_sha256$itérations$sel$hash ») : les mots de passe
 * de l'application Django restent valides après import-django.
 *
 * Le nombre d'itérations se règle par PASSWORD_ITERATIONS : la valeur par défaut prendrait
 * plusieurs secondes sur le cœur ARMv6 du Pi 1 (voir la commande calibrer-hachage).
 */
public final class PasswordHasher {
    public static final int MIN_LENGTH = 12;
    private static final String ALGORITHM = "pbkdf2_sha256";
    private static final String SALT_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final int iterations;

    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(String password) {
        StringBuilder salt = new StringBuilder();
        for (int i = 0; i < 22; i++) {
            salt.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
        }
        return encode(password, salt.toString(), iterations);
    }

    public boolean verify(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !parts[0].equals(ALGORITHM)) {
            return false;
        }
        String expected = encode(password, parts[2], Integer.parseInt(parts[1]));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), encoded.getBytes(StandardCharsets.UTF_8));
    }

    /** Vrai si le hash a été calculé avec un autre nombre d'itérations (à ré-encoder à la connexion). */
    public boolean needsRehash(String encoded) {
        String[] parts = encoded.split("\\$");
        return parts.length != 4 || !parts[0].equals(ALGORITHM) || Integer.parseInt(parts[1]) != iterations;
    }

    /** Calcul factice, pour ne pas révéler par le temps de réponse si un compte existe. */
    public void dummyHash(String password) {
        encode(password == null ? "" : password, "dummysaltdummysalt", iterations);
    }

    static String encode(String password, String salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt.getBytes(StandardCharsets.UTF_8), iterations, 256);
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return ALGORITHM + "$" + iterations + "$" + salt + "$" + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Règles minimales : longueur, et pas uniquement des chiffres. Renvoie un message d'erreur ou null. */
    public static String validate(String password, User user) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "Le mot de passe doit contenir au moins " + MIN_LENGTH + " caractères.";
        }
        if (password.chars().allMatch(Character::isDigit)) {
            return "Le mot de passe ne peut pas être entièrement numérique.";
        }
        if (user != null && (password.equalsIgnoreCase(user.username) || password.equalsIgnoreCase(user.email))) {
            return "Le mot de passe est trop proche de l'identifiant.";
        }
        return null;
    }
}
