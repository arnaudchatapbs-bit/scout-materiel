package scout.materiel.inventory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import scout.materiel.accounts.Roles;
import scout.materiel.accounts.User;
import scout.materiel.common.ApiError;
import scout.materiel.common.Text;
import scout.materiel.db.Database;
import scout.materiel.db.Tx;
import scout.materiel.loans.LineStatus;

/** Consultation de l'inventaire (tous) et gestion du matériel et des catégories (admin). */
public class InventoryService {
    private final Database db;

    public InventoryService(Database db) {
        this.db = db;
    }

    public long activeItemCount() {
        return db.read(tx -> tx.count("SELECT COUNT(*) FROM items WHERE is_active = 1"));
    }

    // Liste du matériel ---------------------------------------------------------------------

    /** Une famille ou sous-catégorie avec son nombre d'objets visibles. */
    public static class CategoryCount {
        public long id;
        public String name;
        public long count;

        CategoryCount(Category category, long count) {
            this.id = category.id;
            this.name = category.name;
            this.count = count;
        }
    }

    public static class ItemGroup {
        public String heading;
        public List<Item> items = new ArrayList<>();
    }

    /**
     * Page « Matériel » : sans catégorie ni recherche → une carte par famille ;
     * sinon objets groupés par sous-catégorie, avec les sous-catégories en puces.
     */
    public static class ListPage {
        public Category.Ref category;
        public List<Category.Ref> breadcrumb = new ArrayList<>();
        public List<CategoryCount> families;
        public List<CategoryCount> subcategories;
        public List<ItemGroup> groups;
        public Integer resultCount;
    }

    public ListPage listPage(User user, Long categoryId, String query, Map<Long, Integer> cart) {
        return db.read(tx -> {
            CategoryTree tree = CategoryTree.load(tx);
            Category category = null;
            if (categoryId != null) {
                category = tree.get(categoryId);
                if (category == null) {
                    throw ApiError.notFound("Catégorie introuvable.");
                }
            }
            boolean admin = Roles.isAdmin(user);
            // Dans une catégorie, ne charger que sa sous-arborescence (moins d'objets à traiter sur le Pi)
            List<Item> items = Visibility.visibleItems(tx, user, tree, category == null ? null : tree.descendantIds(category.id), null);
            markCart(items, cart);

            ListPage page = new ListPage();
            String q = Text.orEmpty(query);
            if (category == null && q.isEmpty()) {
                page.families = counts(tree.roots(), items, tree, admin);
                return page;
            }
            if (!q.isEmpty()) {
                items = Search.filter(items, q, tree);
            }
            Map<Long, ItemGroup> groups = new LinkedHashMap<>();
            for (Item item : items) {
                ItemGroup group = groups.computeIfAbsent(item.categoryId, id -> new ItemGroup());
                group.items.add(item);
            }
            for (Map.Entry<Long, ItemGroup> entry : groups.entrySet()) {
                String heading = tree.path(entry.getKey(), category == null ? null : category.id);
                entry.getValue().heading = heading.isEmpty() ? category.name : heading;
            }
            page.groups = groups.values().stream().sorted(Comparator.comparing(g -> g.heading.toLowerCase())).collect(Collectors.toList());
            page.resultCount = items.size();
            if (category != null) {
                page.category = new Category.Ref(category);
                page.breadcrumb = tree.ancestors(category.id).stream().map(Category.Ref::new).collect(Collectors.toList());
                page.subcategories = counts(tree.children(category.id), items, tree, admin);
            }
            return page;
        });
    }

    private static List<CategoryCount> counts(List<Category> categories, List<Item> items, CategoryTree tree, boolean admin) {
        List<CategoryCount> result = new ArrayList<>();
        for (Category category : categories) {
            Set<Long> ids = tree.descendantIds(category.id);
            long count = items.stream().filter(i -> ids.contains(i.categoryId)).count();
            if (count > 0 || admin) {
                result.add(new CategoryCount(category, count));
            }
        }
        return result;
    }

    /** Ajoute inCart (quantité déjà au panier) et addable (combien on peut encore ajouter). */
    static void markCart(List<Item> items, Map<Long, Integer> cart) {
        for (Item item : items) {
            item.inCart = cart.getOrDefault(item.id, 0);
            item.addable = item.reservable ? Math.max(item.available - item.inCart, 0) : 0;
        }
    }

    // Fiche objet ---------------------------------------------------------------------------

    public static class LineSummary {
        public long requestId;
        public String requesterName;
        public int quantity;
        public String date;
    }

    public static class ItemPage {
        public Item item;
        public List<Category.Ref> breadcrumb;
        /** Admin uniquement. */
        public List<LineSummary> reservedLines;
        public List<LineSummary> pendingLines;
    }

    /** Fiche visible par l'utilisateur ; 404 pour un invité si tout est réservé. */
    public ItemPage itemPage(User user, long id, Map<Long, Integer> cart) {
        return db.read(tx -> {
            CategoryTree tree = CategoryTree.load(tx);
            List<Item> found = Visibility.visibleItems(tx, user, tree, null, List.of(id));
            if (found.isEmpty()) {
                throw ApiError.notFound("Objet introuvable.");
            }
            markCart(found, cart);
            ItemPage page = new ItemPage();
            page.item = found.get(0);
            page.breadcrumb = tree.ancestors(page.item.categoryId).stream().map(Category.Ref::new).collect(Collectors.toList());
            if (Roles.isAdmin(user)) {
                page.reservedLines = lineSummaries(tx, id, LineStatus.RESERVED, "l.reserved_at");
                page.pendingLines = lineSummaries(tx, id, LineStatus.PENDING, "r.created_at");
            }
            return page;
        });
    }

    private static List<LineSummary> lineSummaries(Tx tx, long itemId, LineStatus status, String dateColumn) throws SQLException {
        return tx.list("SELECT l.request_id, l.quantity, " + dateColumn + " AS date, u.username, u.first_name, u.last_name"
                        + " FROM request_lines l JOIN requests r ON r.id = l.request_id JOIN users u ON u.id = r.requester_id"
                        + " WHERE l.item_id = ? AND l.status = ? ORDER BY r.created_at DESC",
                rs -> {
                    LineSummary s = new LineSummary();
                    s.requestId = rs.getLong("request_id");
                    s.quantity = rs.getInt("quantity");
                    s.date = rs.getString("date");
                    String full = (rs.getString("first_name") + " " + rs.getString("last_name")).trim();
                    s.requesterName = full.isEmpty() ? rs.getString("username") : full;
                    return s;
                }, itemId, status.code);
    }

    // Gestion du matériel (admin) ---------------------------------------------------------------

    /** Tous les objets, y compris inactifs, avec leur disponibilité (filtres facultatifs). */
    public List<Item> adminItems(Long categoryId, String query, String state) {
        return db.read(tx -> {
            CategoryTree tree = CategoryTree.load(tx);
            StringBuilder sql = new StringBuilder("SELECT * FROM items WHERE 1 = 1");
            List<Object> params = new ArrayList<>();
            if (categoryId != null) {
                Set<Long> ids = tree.descendantIds(categoryId);
                sql.append(" AND category_id IN (").append(Tx.placeholders(ids)).append(")");
                params.add(ids);
            }
            if ("actifs".equals(state)) {
                sql.append(" AND is_active = 1");
            } else if ("inactifs".equals(state)) {
                sql.append(" AND is_active = 0");
            }
            sql.append(" ORDER BY name COLLATE NOCASE");
            List<Item> items = Visibility.annotate(tx, tx.list(sql.toString(), Item::map, params.toArray()), tree, true);
            return Search.filter(items, query, tree);
        });
    }

    public Item adminItem(long id) {
        return db.read(tx -> {
            Item item = tx.one("SELECT * FROM items WHERE id = ?", Item::map, id).orElseThrow(() -> ApiError.notFound("Objet introuvable."));
            return Visibility.annotate(tx, List.of(item), CategoryTree.load(tx), true).get(0);
        });
    }

    public static class ItemForm {
        public String name;
        public Long categoryId;
        public String reference;
        public Integer quantity;
        public String location;
        public String comment;
        public String remark;
        public boolean isConsumable;
        public boolean active = true;
    }

    public Item saveItem(Long id, ItemForm form) {
        long savedId = db.tx(tx -> {
            List<String> errors = new ArrayList<>();
            String name = Text.orEmpty(form.name);
            String reference = Text.orEmpty(form.reference);
            if (name.isEmpty()) {
                errors.add("Le nom est obligatoire.");
            }
            if (form.categoryId == null || tx.count("SELECT COUNT(*) FROM categories WHERE id = ?", form.categoryId) == 0) {
                errors.add("Choisissez une catégorie.");
            }
            int quantity = form.quantity == null ? 1 : form.quantity;
            if (quantity < 0) {
                errors.add("La quantité ne peut pas être négative.");
            }
            if (!reference.isEmpty() && tx.count("SELECT COUNT(*) FROM items WHERE reference = ? AND id <> ?",
                    reference, id == null ? 0 : id) > 0) {
                errors.add("La référence " + reference + " est déjà utilisée par un autre objet.");
            }
            if (!errors.isEmpty()) {
                throw ApiError.invalid(errors);
            }
            String now = Instant.now().toString();
            Object ref = reference.isEmpty() ? null : reference;
            if (id == null) {
                return tx.insert("INSERT INTO items (name, category_id, reference, quantity, location, comment, remark, is_consumable,"
                                + " is_active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        name, form.categoryId, ref, quantity, Text.orEmpty(form.location), Text.orEmpty(form.comment),
                        Text.orEmpty(form.remark), form.isConsumable, form.active, now, now);
            }
            int updated = tx.update("UPDATE items SET name = ?, category_id = ?, reference = ?, quantity = ?, location = ?, comment = ?,"
                            + " remark = ?, is_consumable = ?, is_active = ?, updated_at = ? WHERE id = ?",
                    name, form.categoryId, ref, quantity, Text.orEmpty(form.location), Text.orEmpty(form.comment),
                    Text.orEmpty(form.remark), form.isConsumable, form.active, now, id);
            if (updated == 0) {
                throw ApiError.notFound("Objet introuvable.");
            }
            return id;
        });
        return adminItem(savedId);
    }

    /** Supprime un objet jamais demandé ; sinon il faut le désactiver (l'historique des demandes en dépend). */
    public void deleteItem(long id) {
        db.tx(tx -> {
            if (tx.count("SELECT COUNT(*) FROM request_lines WHERE item_id = ?", id) > 0) {
                throw ApiError.invalid("Cet objet figure dans des demandes : désactivez-le plutôt que de le supprimer.");
            }
            if (tx.update("DELETE FROM items WHERE id = ?", id) == 0) {
                throw ApiError.notFound("Objet introuvable.");
            }
            return null;
        });
    }

    // Catégories (admin) ---------------------------------------------------------------------

    public static class CategoryRow {
        public long id;
        public String name;
        public Long parentId;
        public String path;
        public int depth;
        public boolean consumable;
        /** Consommable par héritage d'une catégorie parente. */
        public boolean inheritedConsumable;
        public long itemCount;
        public int childCount;
    }

    /** Toutes les catégories dans l'ordre de l'arborescence (parent puis enfants). */
    public List<CategoryRow> categories() {
        return db.read(tx -> {
            CategoryTree tree = CategoryTree.load(tx);
            Map<Long, Long> itemCounts = new LinkedHashMap<>();
            for (long[] row : tx.list("SELECT category_id, COUNT(*) FROM items GROUP BY category_id",
                    rs -> new long[]{rs.getLong(1), rs.getLong(2)})) {
                itemCounts.put(row[0], row[1]);
            }
            List<CategoryRow> rows = new ArrayList<>();
            addRows(tree, tree.roots(), itemCounts, rows);
            return rows;
        });
    }

    private static void addRows(CategoryTree tree, List<Category> level, Map<Long, Long> itemCounts, List<CategoryRow> rows) {
        for (Category category : level) {
            CategoryRow row = new CategoryRow();
            row.id = category.id;
            row.name = category.name;
            row.parentId = category.parentId;
            row.path = tree.path(category.id);
            row.depth = tree.depth(category.id);
            row.consumable = category.consumable;
            row.inheritedConsumable = !category.consumable && tree.isConsumable(category.id);
            row.itemCount = tree.descendantIds(category.id).stream().mapToLong(id -> itemCounts.getOrDefault(id, 0L)).sum();
            row.childCount = tree.children(category.id).size();
            rows.add(row);
            addRows(tree, tree.children(category.id), itemCounts, rows);
        }
    }

    public static class CategoryForm {
        public String name;
        public Long parentId;
        public boolean consumable;
    }

    public void saveCategory(Long id, CategoryForm form) {
        db.tx(tx -> {
            String name = Text.orEmpty(form.name);
            CategoryTree tree = CategoryTree.load(tx);
            List<String> errors = new ArrayList<>();
            if (name.isEmpty()) {
                errors.add("Le nom est obligatoire.");
            }
            if (form.parentId != null) {
                if (tree.get(form.parentId) == null) {
                    errors.add("Catégorie parente introuvable.");
                } else if (id != null && tree.descendantIds(id).contains(form.parentId)) {
                    errors.add("Une catégorie ne peut pas être rangée dans elle-même ou dans une de ses sous-catégories.");
                } else if (tree.depth(form.parentId) >= 3) {
                    errors.add("L'arborescence est limitée à 4 niveaux (Famille → Sous-catégorie 3).");
                }
            }
            long siblings = tx.count("SELECT COUNT(*) FROM categories WHERE COALESCE(parent_id, 0) = ? AND name = ? COLLATE NOCASE AND id <> ?",
                    form.parentId == null ? 0 : form.parentId, name, id == null ? 0 : id);
            if (siblings > 0) {
                errors.add("Une catégorie « " + name + " » existe déjà à cet endroit.");
            }
            if (!errors.isEmpty()) {
                throw ApiError.invalid(errors);
            }
            if (id == null) {
                tx.insert("INSERT INTO categories (name, parent_id, is_consumable) VALUES (?, ?, ?)", name, form.parentId, form.consumable);
            } else if (tx.update("UPDATE categories SET name = ?, parent_id = ?, is_consumable = ? WHERE id = ?",
                    name, form.parentId, form.consumable, id) == 0) {
                throw ApiError.notFound("Catégorie introuvable.");
            }
            return null;
        });
    }

    /** Seule une catégorie vide (ni sous-catégorie ni objet) peut être supprimée. */
    public void deleteCategory(long id) {
        db.tx(tx -> {
            if (tx.count("SELECT COUNT(*) FROM categories WHERE parent_id = ?", id) > 0
                    || tx.count("SELECT COUNT(*) FROM items WHERE category_id = ?", id) > 0) {
                throw ApiError.invalid("Cette catégorie n'est pas vide : déplacez d'abord ses objets et sous-catégories.");
            }
            if (tx.update("DELETE FROM categories WHERE id = ?", id) == 0) {
                throw ApiError.notFound("Catégorie introuvable.");
            }
            return null;
        });
    }
}
