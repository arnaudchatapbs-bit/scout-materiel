package scout.materiel.inventory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import scout.materiel.common.ApiError;
import scout.materiel.common.Text;
import scout.materiel.db.Database;
import scout.materiel.db.Tx;

/**
 * Import de la nomenclature Excel du matériel (onglet « Nomenclature »).
 *
 * Idempotent : un objet est identifié par sa référence si elle existe, sinon par
 * (catégorie, nom). Relancer l'import met à jour les objets existants.
 */
public class NomenclatureImporter {
    static final List<String> LEVEL_COLUMNS = List.of("famille", "sous-categorie 1", "sous-categorie 2", "sous-categorie 3");
    static final Map<String, String> FIELD_COLUMNS = new LinkedHashMap<>();
    static final String FREE_SLOT = "emplacement libre";
    static final Set<String> CONSUMABLE_ROOTS = Set.of("alimentation");
    static final Set<String> CONSUMABLE_CATEGORIES = Set.of("consommables");

    static {
        FIELD_COLUMNS.put("name", "objet");
        FIELD_COLUMNS.put("reference", "reference");
        FIELD_COLUMNS.put("quantity", "quantite");
        FIELD_COLUMNS.put("location", "localisation");
        FIELD_COLUMNS.put("comment", "commentaire");
        FIELD_COLUMNS.put("remark", "remarque");
    }

    private final Database db;

    public NomenclatureImporter(Database db) {
        this.db = db;
    }

    /** Compte rendu de l'import, affiché par la commande et par la page d'import. */
    public static class Report {
        public boolean dryRun;
        public int categoriesCreated;
        public int created;
        public int updated;
        public int skipped;
        public List<String> merged = new ArrayList<>();
        public List<String> doubtful = new ArrayList<>();
        public List<String> warnings = new ArrayList<>();

        public String toText() {
            StringBuilder out = new StringBuilder();
            if (dryRun) {
                out.append("Simulation (--dry-run) : rien n'a été enregistré.\n");
            }
            out.append("Catégories créées : ").append(categoriesCreated).append('\n')
                    .append("Objets créés : ").append(created).append('\n')
                    .append("Objets mis à jour : ").append(updated).append('\n')
                    .append("Lignes ignorées (vides, « X », emplacement libre) : ").append(skipped).append('\n');
            if (!merged.isEmpty()) {
                out.append("Lignes fusionnées :\n");
                merged.forEach(m -> out.append("  ").append(m).append('\n'));
            }
            if (!doubtful.isEmpty()) {
                out.append("Doublons signalés à vérifier :\n");
                doubtful.forEach(d -> out.append("  ").append(d).append('\n'));
            }
            warnings.forEach(w -> out.append(w).append('\n'));
            return out.append(dryRun ? "Simulation terminée : relancez sans --dry-run pour enregistrer." : "Import terminé.").toString();
        }
    }

    static final class ImportRow {
        int line;
        List<String> path;
        String name;
        String reference;
        int quantity;
        String location;
        String comment;
        String remark;
        List<Integer> mergedLines = new ArrayList<>();
    }

    public Report run(InputStream xlsx, String sheetName, boolean dryRun) {
        Report report = new Report();
        report.dryRun = dryRun;
        List<ImportRow> rows = readRows(xlsx, sheetName == null ? "Nomenclature" : sheetName, report);
        List<ImportRow> merged = mergeDuplicates(rows, report);
        try {
            db.tx(tx -> {
                write(tx, merged, report);
                if (dryRun) {
                    // Annule la transaction (simulation)
                    throw new DryRun();
                }
                return null;
            });
        } catch (DryRun ignored) {
            // Rien n'est enregistré
        }
        for (ImportRow row : merged) {
            if (!row.mergedLines.isEmpty()) {
                List<Integer> lines = new ArrayList<>();
                lines.add(row.line);
                lines.addAll(row.mergedLines);
                report.merged.add(String.join(" › ", row.path) + " › " + row.name + " : lignes "
                        + lines.stream().map(String::valueOf).collect(Collectors.joining(", ")) + " → quantité " + row.quantity);
            }
            if (Text.normalize(row.remark).contains("doublon")) {
                report.doubtful.add("Ligne " + row.line + " — " + String.join(" › ", row.path) + " › " + row.name + " : " + row.remark);
            }
        }
        return report;
    }

    private static final class DryRun extends RuntimeException {
        DryRun() {
            super(null, null, false, false);
        }
    }

    // Lecture -------------------------------------------------------------------------------

    List<ImportRow> readRows(InputStream xlsx, String sheetName, Report report) {
        try (ReadableWorkbook workbook = new ReadableWorkbook(xlsx)) {
            Optional<Sheet> sheet = workbook.findSheet(sheetName);
            if (sheet.isEmpty()) {
                String names = workbook.getSheets().map(Sheet::getName).collect(Collectors.joining(", "));
                throw ApiError.invalid("Onglet « " + sheetName + " » introuvable (onglets : " + names + ")");
            }
            try (Stream<Row> stream = sheet.get().openStream()) {
                List<ImportRow> rows = new ArrayList<>();
                Map<String, Integer> columns = null;
                for (Row values : (Iterable<Row>) stream::iterator) {
                    if (columns == null) {
                        columns = mapColumns(values);
                        continue;
                    }
                    ImportRow row = parseRow(values, columns, report);
                    if (row != null) {
                        rows.add(row);
                    }
                }
                if (columns == null) {
                    throw ApiError.invalid("L'onglet « " + sheetName + " » est vide.");
                }
                return rows;
            }
        } catch (IOException e) {
            throw ApiError.invalid("Impossible de lire le fichier Excel : " + e.getMessage());
        }
    }

    /** Associe chaque champ attendu à l'index de sa colonne, d'après l'en-tête. */
    static Map<String, Integer> mapColumns(Row header) {
        List<String> normalized = new ArrayList<>();
        for (int i = 0; i < header.getCellCount(); i++) {
            normalized.add(Text.normalize(cellText(header, i)));
        }
        Map<String, Integer> columns = new HashMap<>();
        for (String key : LEVEL_COLUMNS) {
            int index = normalized.indexOf(key);
            columns.put(key, index >= 0 ? index : null);
        }
        for (Map.Entry<String, String> field : FIELD_COLUMNS.entrySet()) {
            Integer found = null;
            for (int i = 0; i < normalized.size(); i++) {
                if (normalized.get(i).startsWith(field.getValue())) {
                    found = i;
                    break;
                }
            }
            columns.put(field.getKey(), found);
        }
        List<String> missing = new ArrayList<>();
        if (columns.get("famille") == null) {
            missing.add("Famille");
        }
        if (columns.get("name") == null) {
            missing.add("Objet");
        }
        if (!missing.isEmpty()) {
            throw ApiError.invalid("Colonnes obligatoires absentes de l'en-tête : " + String.join(", ", missing));
        }
        return columns;
    }

    private static ImportRow parseRow(Row values, Map<String, Integer> columns, Report report) {
        boolean empty = true;
        for (int i = 0; i < values.getCellCount(); i++) {
            if (!cellText(values, i).isEmpty()) {
                empty = false;
                break;
            }
        }
        if (empty) {
            return null;
        }
        List<String> path = new ArrayList<>();
        for (String level : LEVEL_COLUMNS) {
            String value = get(values, columns, level);
            if (!value.isEmpty()) {
                path.add(value);
            }
        }
        String name = get(values, columns, "name");
        if (path.isEmpty() || name.isEmpty() || Text.normalize(name).equals("x")
                || path.stream().anyMatch(p -> Text.normalize(p).equals(FREE_SLOT))) {
            report.skipped++;
            return null;
        }
        ImportRow row = new ImportRow();
        row.line = values.getRowNum();
        row.path = path;
        row.name = name;
        row.reference = get(values, columns, "reference");
        row.quantity = parseQuantity(get(values, columns, "quantity"), row.line, report);
        row.location = get(values, columns, "location");
        row.comment = get(values, columns, "comment");
        row.remark = get(values, columns, "remark");
        return row;
    }

    private static String get(Row values, Map<String, Integer> columns, String key) {
        Integer index = columns.get(key);
        return index == null ? "" : cellText(values, index);
    }

    /** Texte d'une cellule ; « 3.0 » devient « 3 » (nombres entiers saisis dans Excel). */
    static String cellText(Row row, int index) {
        if (index >= row.getCellCount()) {
            return "";
        }
        Cell cell = row.getCell(index);
        if (cell == null || cell.getType() == CellType.EMPTY) {
            return "";
        }
        if (cell.getType() == CellType.NUMBER) {
            BigDecimal number = cell.asNumber();
            BigDecimal stripped = number.stripTrailingZeros();
            return stripped.scale() <= 0 ? stripped.toBigInteger().toString() : stripped.toPlainString();
        }
        String text = cell.getText();
        return text == null ? "" : text.trim();
    }

    static int parseQuantity(String raw, int line, Report report) {
        if (raw.isEmpty()) {
            return 1;
        }
        try {
            return Math.max((int) Double.parseDouble(raw.replace(",", ".")), 1);
        } catch (NumberFormatException e) {
            report.warnings.add("Ligne " + line + " : quantité « " + raw + " » illisible, 1 retenu");
            return 1;
        }
    }

    // Fusion des doublons ----------------------------------------------------------------------

    /** Fusionne les lignes identiques sans référence (quantités additionnées). */
    static List<ImportRow> mergeDuplicates(List<ImportRow> rows, Report report) {
        Map<String, ImportRow> byKey = new LinkedHashMap<>();
        for (ImportRow row : rows) {
            String key = row.reference.isEmpty()
                    ? "name:" + row.path.stream().map(Text::normalize).collect(Collectors.joining("/")) + ":" + Text.normalize(row.name)
                    : "ref:" + row.reference.toUpperCase();
            ImportRow existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, row);
            } else if (!row.reference.isEmpty()) {
                report.warnings.add("Ligne " + row.line + " : référence " + row.reference + " déjà utilisée ligne " + existing.line + ", ligne ignorée");
            } else {
                existing.quantity += row.quantity;
                existing.mergedLines.add(row.line);
                existing.location = mergeText(existing.location, row.location);
                existing.comment = mergeText(existing.comment, row.comment);
                existing.remark = mergeText(existing.remark, row.remark);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static String mergeText(String current, String added) {
        if (added.isEmpty() || current.contains(added)) {
            return current;
        }
        return current.isEmpty() ? added : current + "\n" + added;
    }

    // Écriture --------------------------------------------------------------------------------

    private void write(Tx tx, List<ImportRow> rows, Report report) throws SQLException {
        Map<List<String>, Long> cache = new HashMap<>();
        String now = Instant.now().toString();
        for (ImportRow row : rows) {
            long categoryId = category(tx, row.path, cache, report);
            Optional<Long> existing = row.reference.isEmpty()
                    ? tx.one("SELECT id FROM items WHERE category_id = ? AND name = ? COLLATE NOCASE AND reference IS NULL",
                    rs -> rs.getLong(1), categoryId, row.name)
                    : tx.one("SELECT id FROM items WHERE reference = ?", rs -> rs.getLong(1), row.reference);
            Object reference = row.reference.isEmpty() ? null : row.reference;
            if (existing.isEmpty()) {
                tx.insert("INSERT INTO items (name, category_id, reference, quantity, location, comment, remark, created_at, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        row.name, categoryId, reference, row.quantity, row.location, row.comment, row.remark, now, now);
                report.created++;
            } else {
                tx.update("UPDATE items SET name = ?, category_id = ?, reference = ?, quantity = ?, location = ?, comment = ?, remark = ?,"
                                + " updated_at = ? WHERE id = ?",
                        row.name, categoryId, reference, row.quantity, row.location, row.comment, row.remark, now, existing.get());
                report.updated++;
            }
        }
    }

    private long category(Tx tx, List<String> path, Map<List<String>, Long> cache, Report report) throws SQLException {
        Long cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        Long parent = path.size() > 1 ? category(tx, path.subList(0, path.size() - 1), cache, report) : null;
        String name = path.get(path.size() - 1);
        Optional<Long> found = tx.one("SELECT id FROM categories WHERE COALESCE(parent_id, 0) = ? AND name = ? COLLATE NOCASE",
                rs -> rs.getLong(1), parent == null ? 0 : parent, name);
        long id;
        if (found.isPresent()) {
            id = found.get();
        } else {
            boolean consumable = parent == null && CONSUMABLE_ROOTS.contains(Text.normalize(name))
                    || CONSUMABLE_CATEGORIES.contains(Text.normalize(name));
            id = tx.insert("INSERT INTO categories (name, parent_id, is_consumable) VALUES (?, ?, ?)", name, parent, consumable);
            report.categoriesCreated++;
        }
        cache.put(new ArrayList<>(path), id);
        return id;
    }
}
