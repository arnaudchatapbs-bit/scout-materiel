package scout.materiel.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scout.materiel.Fixtures;
import scout.materiel.common.ApiError;
import scout.materiel.db.Database;

class NomenclatureImporterTest {
    static final List<Object> HEADER = Arrays.asList("Famille", "Sous-catégorie 1", "Sous-catégorie 2", "Sous-catégorie 3", "Objet",
            "Référence", "Quantité", "Localisation", "Commentaire", "Remarque (réorganisation)", "Ancienne famille");

    static List<List<Object>> rows() {
        List<List<Object>> rows = new ArrayList<>();
        rows.add(Arrays.asList("Alimentation", "Épicerie sucrée", null, null, "miel", null, null, null, null, null, "Alimentation"));
        rows.add(Arrays.asList("Alimentation", "Emplacement libre", null, null, "X", null, null, null, null, "Ligne vide", "Alimentation"));
        rows.add(Arrays.asList("Cuisine collective", "Cuisson et batterie", "Marmites", "Grandes", "D35 H33 30L", "CGM1", 1, "A.3", null, null, null));
        rows.add(Arrays.asList("Cuisine collective", "Cuisson et batterie", "Marmites", "Petites", "D29 H14 9L", "CPM7", 1, "C.2", "neuve", null, null));
        rows.add(Arrays.asList("Cuisine collective", "Ustensiles", null, null, "économes", null, 5, null, null, null, null));
        for (int i = 0; i < 3; i++) {
            rows.add(Arrays.asList("Cuisine collective", "Service", "Plats", null, "Plat de service", null, null, null, null, null, null));
        }
        rows.add(Arrays.asList("Camping", "Tentes", null, null, "X", null, null, null, null, null, null));
        rows.add(Arrays.asList("Camping", "Fixation", null, null, "maillet", null, null, null, null, "Doublon possible avec Outillage", null));
        rows.add(Arrays.asList("Hygiène/santé", "Consommables", null, null, "éponge", null, null, null, null, null, null));
        rows.add(Arrays.asList(null, null, null, null, null, null, null, null, null, null, null));
        return rows;
    }

    Fixtures f;
    Database db;
    NomenclatureImporter importer;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        db = f.services.db;
        importer = new NomenclatureImporter(db);
    }

    static InputStream workbook(String sheet, List<Object> header, List<List<Object>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Workbook wb = new Workbook(out, "test", "1.0");
        Worksheet ws = wb.newWorksheet(sheet);
        List<List<Object>> all = new ArrayList<>();
        all.add(header);
        all.addAll(rows);
        for (int r = 0; r < all.size(); r++) {
            for (int c = 0; c < all.get(r).size(); c++) {
                Object value = all.get(r).get(c);
                if (value instanceof Number) {
                    ws.value(r, c, (Number) value);
                } else if (value != null) {
                    ws.value(r, c, value.toString());
                }
            }
        }
        wb.finish();
        return new ByteArrayInputStream(out.toByteArray());
    }

    NomenclatureImporter.Report run(List<List<Object>> rows) throws IOException {
        return importer.run(workbook("Nomenclature", HEADER, rows), "Nomenclature", false);
    }

    Item item(String where, Object value) {
        return db.read(tx -> tx.one("SELECT * FROM items WHERE " + where, Item::map, value)).orElseThrow();
    }

    long count(String sql) {
        return db.read(tx -> tx.count(sql));
    }

    String path(Item item) {
        return db.read(tx -> CategoryTree.load(tx).path(item.categoryId));
    }

    boolean consumable(Item item) {
        return item.isConsumable || db.read(tx -> CategoryTree.load(tx).isConsumable(item.categoryId));
    }

    @Test
    void buildsCategoryTree() throws IOException {
        run(rows());
        Item marmite = item("reference = ?", "CGM1");
        assertEquals("Cuisine collective › Cuisson et batterie › Marmites › Grandes", path(marmite));
        assertEquals("A.3", marmite.location);
        assertEquals(4, count("SELECT COUNT(*) FROM categories WHERE parent_id IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM categories WHERE name = 'Marmites'"));
    }

    @Test
    void skipsFreeSlotsAndX() throws IOException {
        NomenclatureImporter.Report report = run(rows());
        assertEquals(0, count("SELECT COUNT(*) FROM items WHERE lower(name) = 'x'"));
        assertEquals(0, count("SELECT COUNT(*) FROM categories WHERE name = 'Emplacement libre'"));
        assertEquals(2, report.skipped);
    }

    @Test
    void emptyQuantityDefaultsToOne() throws IOException {
        run(rows());
        assertEquals(1, item("name = ?", "miel").quantity);
        assertEquals(5, item("name = ?", "économes").quantity);
    }

    @Test
    void mergesIdenticalRowsWithoutReference() throws IOException {
        NomenclatureImporter.Report report = run(rows());
        assertEquals(1, count("SELECT COUNT(*) FROM items WHERE name = 'Plat de service'"));
        assertEquals(3, item("name = ?", "Plat de service").quantity);
        assertEquals(1, report.merged.size());
    }

    @Test
    void flagsConsumables() throws IOException {
        run(rows());
        assertTrue(consumable(item("name = ?", "miel")));
        assertTrue(consumable(item("name = ?", "éponge")));
        assertFalse(consumable(item("reference = ?", "CGM1")));
    }

    @Test
    void reportsFlaggedDuplicates() throws IOException {
        NomenclatureImporter.Report report = run(rows());
        assertTrue(report.toText().contains("Doublons signalés"));
        assertTrue(report.doubtful.get(0).contains("maillet"));
        assertEquals("Doublon possible avec Outillage", item("name = ?", "maillet").remark);
    }

    @Test
    void secondImportCreatesNothing() throws IOException {
        run(rows());
        long categories = count("SELECT COUNT(*) FROM categories");
        long items = count("SELECT COUNT(*) FROM items");
        NomenclatureImporter.Report report = run(rows());
        assertEquals(categories, count("SELECT COUNT(*) FROM categories"));
        assertEquals(items, count("SELECT COUNT(*) FROM items"));
        assertEquals(0, report.created);
        assertEquals(3, item("name = ?", "Plat de service").quantity);
    }

    @Test
    void reimportUpdatesExistingItem() throws IOException {
        run(rows());
        List<List<Object>> rows = rows();
        List<Object> marmite = new ArrayList<>(rows.get(2));
        marmite.set(7, "B.1");
        rows.set(2, marmite);
        run(rows);
        assertEquals("B.1", item("reference = ?", "CGM1").location);
    }

    @Test
    void dryRunWritesNothing() throws IOException {
        NomenclatureImporter.Report report = importer.run(workbook("Nomenclature", HEADER, rows()), "Nomenclature", true);
        assertTrue(report.toText().contains("Simulation"));
        assertEquals(0, count("SELECT COUNT(*) FROM items"));
        assertEquals(0, count("SELECT COUNT(*) FROM categories"));
    }

    @Test
    void missingSheetIsAnError() {
        assertThrows(ApiError.class, () -> importer.run(workbook("Nomenclature", HEADER, rows()), "Inexistant", false));
    }

    @Test
    void missingRequiredColumnIsAnError() {
        assertThrows(ApiError.class, () -> importer.run(
                workbook("Nomenclature", Arrays.asList("Famille", "Référence"), new ArrayList<>()), "Nomenclature", false));
    }

    @Test
    void realNomenclatureFileImportsTwiceIdentically() throws IOException {
        NomenclatureImporter.Report first;
        try (InputStream in = getClass().getResourceAsStream("/nomenclature.xlsx")) {
            first = importer.run(in, "Nomenclature", false);
        }
        assertTrue(first.created > 100, "objets créés : " + first.created);
        try (InputStream in = getClass().getResourceAsStream("/nomenclature.xlsx")) {
            NomenclatureImporter.Report second = importer.run(in, "Nomenclature", false);
            assertEquals(0, second.created);
            assertEquals(0, second.categoriesCreated);
        }
    }
}
