package scout.materiel.common;

import java.util.List;

/**
 * Erreur métier ou d'accès, renvoyée au front en JSON : {"errors": [...]}.
 * Lever une ApiError dans une transaction l'annule entièrement (tout-ou-rien).
 */
public class ApiError extends RuntimeException {
    public final int status;
    public final List<String> messages;

    public ApiError(int status, List<String> messages) {
        super(String.join(" ", messages));
        this.status = status;
        this.messages = List.copyOf(messages);
    }

    public ApiError(int status, String message) {
        this(status, List.of(message));
    }

    /** Règle métier non respectée (400). */
    public static ApiError invalid(String message) {
        return new ApiError(400, message);
    }

    public static ApiError invalid(List<String> messages) {
        return new ApiError(400, messages);
    }

    public static ApiError notFound(String message) {
        return new ApiError(404, message);
    }

    public static ApiError forbidden() {
        return new ApiError(403, "Accès réservé à l'administrateur.");
    }
}
