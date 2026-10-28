package scout.materiel.loans;

import com.fasterxml.jackson.annotation.JsonValue;

/** Statut propre à chaque ligne de demande, décidé par l'admin. */
public enum LineStatus {
    PENDING("en_attente", "En attente"),
    RESERVED("reservee", "Réservé"),
    REFUSED("refusee", "Refusé"),
    CANCELLED("annulee", "Annulé"),
    RETURNED("rendue", "Rendu");

    public final String code;
    public final String label;

    LineStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @JsonValue
    public String code() {
        return code;
    }

    public static LineStatus of(String code) {
        for (LineStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Statut inconnu : " + code);
    }
}
