package scout.materiel.loans;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import scout.materiel.db.Tx;

/** Disponible = quantité totale − somme des lignes « réservé ». Les lignes en attente ne bloquent rien. */
public final class Availability {
    private Availability() {
    }

    /** {objet: quantité réservée}. */
    public static Map<Long, Integer> reservedQuantities(Tx tx, Collection<Long> itemIds) throws SQLException {
        return quantitiesByItem(tx, LineStatus.RESERVED, itemIds);
    }

    /** {objet: quantité demandée en attente} — indicatif, pour l'admin. */
    public static Map<Long, Integer> pendingQuantities(Tx tx, Collection<Long> itemIds) throws SQLException {
        return quantitiesByItem(tx, LineStatus.PENDING, itemIds);
    }

    private static Map<Long, Integer> quantitiesByItem(Tx tx, LineStatus status, Collection<Long> itemIds) throws SQLException {
        Map<Long, Integer> totals = new HashMap<>();
        if (itemIds != null && itemIds.isEmpty()) {
            return totals;
        }
        String sql = "SELECT item_id, SUM(quantity) FROM request_lines WHERE status = ?"
                + (itemIds == null ? "" : " AND item_id IN (" + Tx.placeholders(itemIds) + ")")
                + " GROUP BY item_id";
        Object[] params = itemIds == null ? new Object[]{status.code} : new Object[]{status.code, itemIds};
        List<long[]> rows = tx.list(sql, rs -> new long[]{rs.getLong(1), rs.getLong(2)}, params);
        for (long[] row : rows) {
            totals.put(row[0], (int) row[1]);
        }
        return totals;
    }
}
