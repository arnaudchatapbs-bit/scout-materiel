package scout.materiel.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Objet de l'inventaire, avec sa disponibilité calculée (voir Visibility).
 * Les champs réservés à l'admin (remark, pending) restent null pour les invités.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Item {
    public long id;
    public String name;
    public long categoryId;
    public String reference;
    public int quantity;
    public String location;
    public String comment;
    public String remark;
    public boolean isConsumable;
    public boolean active;

    // Calculés
    public String categoryPath;
    public boolean reservable;
    public int reserved;
    public int available;
    public Integer pending;
    public int inCart;
    /** Combien on peut encore ajouter au panier. */
    public int addable;

    public static Item map(ResultSet rs) throws SQLException {
        Item item = new Item();
        item.id = rs.getLong("id");
        item.name = rs.getString("name");
        item.categoryId = rs.getLong("category_id");
        item.reference = rs.getString("reference");
        item.quantity = rs.getInt("quantity");
        item.location = rs.getString("location");
        item.comment = rs.getString("comment");
        item.remark = rs.getString("remark");
        item.isConsumable = rs.getInt("is_consumable") == 1;
        item.active = rs.getInt("is_active") == 1;
        return item;
    }
}
