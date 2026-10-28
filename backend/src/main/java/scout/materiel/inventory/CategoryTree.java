package scout.materiel.inventory;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import scout.materiel.db.Tx;

/** Arborescence complète des catégories, chargée en une seule requête. */
public final class CategoryTree {
    private final Map<Long, Category> byId = new HashMap<>();
    private final Map<Long, List<Category>> children = new HashMap<>();
    private static final long ROOT = 0;

    public CategoryTree(List<Category> categories) {
        for (Category category : categories) {
            byId.put(category.id, category);
            children.computeIfAbsent(category.parentId == null ? ROOT : category.parentId, k -> new ArrayList<>()).add(category);
        }
    }

    public static CategoryTree load(Tx tx) throws SQLException {
        return new CategoryTree(tx.list("SELECT * FROM categories ORDER BY name COLLATE NOCASE", Category::map));
    }

    public Category get(long id) {
        return byId.get(id);
    }

    public List<Category> all() {
        return new ArrayList<>(byId.values());
    }

    public List<Category> roots() {
        return children.getOrDefault(ROOT, Collections.emptyList());
    }

    public List<Category> children(long id) {
        return children.getOrDefault(id, Collections.emptyList());
    }

    /** Identifiants de la catégorie et de toutes ses sous-catégories. */
    public Set<Long> descendantIds(long id) {
        Set<Long> ids = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(id);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            if (ids.add(current)) {
                children(current).forEach(c -> stack.push(c.id));
            }
        }
        return ids;
    }

    /** Catégories de la racine jusqu'à celle-ci (incluse). */
    public List<Category> ancestors(long id) {
        List<Category> chain = new ArrayList<>();
        Category node = get(id);
        while (node != null && chain.size() < 50) {
            chain.add(node);
            node = node.parentId == null ? null : get(node.parentId);
        }
        Collections.reverse(chain);
        return chain;
    }

    /** Libellé « A › B › C » ; startAfter retire le début du chemin jusqu'à cette catégorie. */
    public String path(long id, Long startAfter) {
        List<String> names = ancestors(id).stream().map(c -> c.name).collect(Collectors.toList());
        if (startAfter != null) {
            List<String> prefix = ancestors(startAfter).stream().map(c -> c.name).collect(Collectors.toList());
            if (names.size() >= prefix.size() && names.subList(0, prefix.size()).equals(prefix)) {
                names = names.subList(prefix.size(), names.size());
            }
        }
        return String.join(" › ", names);
    }

    public String path(long id) {
        return path(id, null);
    }

    public boolean isConsumable(long id) {
        return ancestors(id).stream().anyMatch(c -> c.consumable);
    }

    public int depth(long id) {
        return ancestors(id).size() - 1;
    }
}
