package scout.materiel.common;

import java.text.Normalizer;

public final class Text {
    private Text() {
    }

    /** Minuscules, sans accents ni espaces superflus — pour comparer ou rechercher des libellés. */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String ascii = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
        return ascii.toLowerCase().trim().replaceAll("\\s+", " ");
    }

    /** « 3 disponibles », « 1 disponible ». */
    public static String plural(long n, String word) {
        return n + " " + word + (n > 1 ? "s" : "");
    }

    public static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
