package scout.materiel.inventory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import scout.materiel.accounts.Roles;
import scout.materiel.accounts.User;
import scout.materiel.db.Tx;
import scout.materiel.loans.Availability;

/**
 * Ce que chaque rôle voit de l'inventaire — seule source de vérité.
 *
 * Admin : tous les objets actifs. Invité : seulement les objets dont il reste au moins
 * un exemplaire non réservé (les consommables restent visibles).
 */
public final class Visibility {
    private Visibility() {
    }

    /** Ajoute reservable, reserved, available (et pending pour l'admin) à chaque objet. */
    public static List<Item> annotate(Tx tx, List<Item> items, CategoryTree tree, boolean admin) throws SQLException {
        List<Long> ids = items.stream().map(i -> i.id).collect(Collectors.toList());
        Map<Long, Integer> reserved = Availability.reservedQuantities(tx, ids);
        Map<Long, Integer> pending = admin ? Availability.pendingQuantities(tx, ids) : Map.of();
        for (Item item : items) {
            item.reservable = !(item.isConsumable || tree.isConsumable(item.categoryId));
            item.reserved = reserved.getOrDefault(item.id, 0);
            item.available = Math.max(item.quantity - item.reserved, 0);
            item.categoryPath = tree.path(item.categoryId);
            if (admin) {
                item.pending = pending.getOrDefault(item.id, 0);
            } else {
                item.remark = null;
            }
        }
        return items;
    }

    /**
     * Objets visibles par l'utilisateur, annotés avec leur disponibilité.
     * categoryIds / itemIds restreignent la recherche (null = pas de restriction).
     */
    public static List<Item> visibleItems(Tx tx, User user, CategoryTree tree, Collection<Long> categoryIds, Collection<Long> itemIds)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM items WHERE is_active = 1");
        List<Object> params = new ArrayList<>();
        if (categoryIds != null) {
            sql.append(" AND category_id IN (").append(Tx.placeholders(categoryIds)).append(")");
            params.add(categoryIds);
        }
        if (itemIds != null) {
            sql.append(" AND id IN (").append(Tx.placeholders(itemIds)).append(")");
            params.add(itemIds);
        }
        sql.append(" ORDER BY name COLLATE NOCASE");
        boolean admin = Roles.isAdmin(user);
        List<Item> items = annotate(tx, tx.list(sql.toString(), Item::map, params.toArray()), tree, admin);
        if (admin) {
            return items;
        }
        return items.stream().filter(i -> !i.reservable || i.available > 0).collect(Collectors.toList());
    }
}
