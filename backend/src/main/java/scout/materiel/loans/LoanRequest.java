package scout.materiel.loans;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Demande de matériel : son statut n'est pas stocké, il est déduit des lignes. */
public class LoanRequest {
    public long id;
    public long requesterId;
    public String requesterName;
    public String requesterEmail;
    public String createdAt;
    public List<Line> lines = new ArrayList<>();

    /** Statut déduit : en attente > réservée > annulée (tout annulé) > terminée. */
    public String getStatus() {
        Set<LineStatus> statuses = lines.stream().map(l -> l.status).collect(Collectors.toSet());
        if (statuses.contains(LineStatus.PENDING)) {
            return "en_attente";
        }
        if (statuses.contains(LineStatus.RESERVED)) {
            return "reservee";
        }
        if (statuses.equals(Set.of(LineStatus.CANCELLED))) {
            return "annulee";
        }
        return "terminee";
    }

    public static class Line {
        public long id;
        public long itemId;
        public String itemName;
        public String itemReference;
        public String itemLocation;
        public int quantity;
        public LineStatus status;
        public String adminComment;
        public String reservedAt;
        public String returnedAt;
        /** Pour l'admin : disponible actuel et quantité totale de l'objet. */
        public Integer itemAvailable;
        public Integer itemQuantity;
    }
}
