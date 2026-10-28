package scout.materiel.inventory;

import java.sql.ResultSet;
import java.sql.SQLException;
import scout.materiel.db.Tx;

/** Nœud de l'arborescence du matériel (Famille → Sous-catégorie 1 → 2 → 3). */
public class Category {
    public long id;
    public String name;
    public Long parentId;
    /** Les objets de cette catégorie (et de ses sous-catégories) ne sont pas réservables. */
    public boolean consumable;

    public static Category map(ResultSet rs) throws SQLException {
        Category category = new Category();
        category.id = rs.getLong("id");
        category.name = rs.getString("name");
        category.parentId = Tx.nullableLong(rs, "parent_id");
        category.consumable = rs.getInt("is_consumable") == 1;
        return category;
    }

    /** Version courte pour le fil d'Ariane et les puces. */
    public static class Ref {
        public final long id;
        public final String name;

        public Ref(Category category) {
            this.id = category.id;
            this.name = category.name;
        }
    }
}
