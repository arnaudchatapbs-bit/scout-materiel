package scout.materiel.inventory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import scout.materiel.common.Text;

public final class Search {
    private Search() {
    }

    /**
     * Objets dont le texte contient tous les mots de la recherche (sans accents ni casse).
     * Filtrage en Java : SQLite ne sait pas ignorer les accents, et l'inventaire reste petit.
     */
    public static List<Item> filter(List<Item> items, String query, CategoryTree tree) {
        List<String> words = Arrays.stream(Text.normalize(query).split(" ")).filter(w -> !w.isEmpty()).collect(Collectors.toList());
        if (words.isEmpty()) {
            return items;
        }
        return items.stream().filter(item -> {
            String haystack = Text.normalize(String.join(" ", item.name, nz(item.reference), nz(item.location),
                    nz(item.comment), tree.path(item.categoryId)));
            return words.stream().allMatch(haystack::contains);
        }).collect(Collectors.toList());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
