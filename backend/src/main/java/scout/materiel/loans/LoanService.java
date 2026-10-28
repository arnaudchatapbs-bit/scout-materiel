package scout.materiel.loans;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import scout.materiel.accounts.Roles;
import scout.materiel.accounts.User;
import scout.materiel.common.ApiError;
import scout.materiel.common.Text;
import scout.materiel.db.Database;
import scout.materiel.db.Tx;
import scout.materiel.inventory.CategoryTree;
import scout.materiel.inventory.Item;
import scout.materiel.inventory.Visibility;

/**
 * Logique métier des demandes : panier, envoi, décisions de l'admin, annulation.
 * Chaque opération est atomique et lève ApiError (liste de messages) sans rien modifier
 * si une règle n'est pas respectée.
 */
public class LoanService {
    public static final String RESERVE = "reserve";
    public static final String REFUSE = "refuse";
    public static final String RETURN = "return";
    public static final String UNRESERVE = "unreserve";
    /** Action possible → statut de départ requis. */
    private static final Map<String, LineStatus> ALLOWED_FROM = Map.of(
            RESERVE, LineStatus.PENDING, REFUSE, LineStatus.PENDING,
            RETURN, LineStatus.RESERVED, UNRESERVE, LineStatus.RESERVED);

    private final Database db;

    public LoanService(Database db) {
        this.db = db;
    }

    // Panier ------------------------------------------------------------------------

    public Map<Long, Integer> cartQuantities(Tx tx, long userId) throws SQLException {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (long[] row : tx.list("SELECT item_id, quantity FROM cart_items WHERE user_id = ?",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, userId)) {
            quantities.put(row[0], (int) row[1]);
        }
        return quantities;
    }

    public int cartCount(long userId) {
        return (int) (long) db.read(tx -> tx.count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", userId));
    }

    /** Contenu du panier ; les articles devenus indisponibles sont retirés (removed = true). */
    public static class CartView {
        public List<Item> items = new ArrayList<>();
        public boolean removed;
    }

    public CartView cart(User user) {
        return db.tx(tx -> {
            Map<Long, Integer> quantities = cartQuantities(tx, user.id);
            Map<Long, Item> visible = indexById(Visibility.visibleItems(tx, user, CategoryTree.load(tx), null, quantities.keySet()));
            CartView view = new CartView();
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                Item item = visible.get(entry.getKey());
                if (item == null || !item.reservable || item.available == 0) {
                    tx.update("DELETE FROM cart_items WHERE user_id = ? AND item_id = ?", user.id, entry.getKey());
                    view.removed = true;
                } else {
                    item.inCart = entry.getValue();
                    view.items.add(item);
                }
            }
            view.items.sort(Comparator.comparing(i -> i.name.toLowerCase()));
            return view;
        });
    }

    /** Résultat d'un ajout ou d'une modification : message à afficher, éventuellement limité au disponible. */
    public static class CartChange {
        public final String message;
        public final boolean limited;
        public final int cartCount;

        CartChange(String message, boolean limited, int cartCount) {
            this.message = message;
            this.limited = limited;
            this.cartCount = cartCount;
        }
    }

    public CartChange addToCart(User user, long itemId, int quantity) {
        return db.tx(tx -> {
            Item item = reservableItem(tx, user, itemId).orElseThrow(() -> ApiError.invalid("Cet article n'est pas disponible."));
            int wanted = cartQuantities(tx, user.id).getOrDefault(itemId, 0) + Math.max(quantity, 1);
            int kept = Math.min(wanted, item.available);
            setCartLine(tx, user.id, itemId, kept);
            int count = (int) tx.count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", user.id);
            if (wanted > item.available) {
                return new CartChange(item.name + " : quantité limitée à " + item.available + " (disponible).", true, count);
            }
            return new CartChange(item.name + " ajouté au panier.", false, count);
        });
    }

    public CartChange updateCart(User user, long itemId, int quantity) {
        return db.tx(tx -> {
            Optional<Item> item = reservableItem(tx, user, itemId);
            String message = null;
            if (item.isEmpty() || quantity <= 0) {
                setCartLine(tx, user.id, itemId, 0);
            } else {
                setCartLine(tx, user.id, itemId, Math.min(quantity, item.get().available));
                if (quantity > item.get().available) {
                    message = item.get().name + " : quantité limitée à " + item.get().available + " (disponible).";
                }
            }
            int count = (int) tx.count("SELECT COUNT(*) FROM cart_items WHERE user_id = ?", user.id);
            return new CartChange(message, message != null, count);
        });
    }

    private static void setCartLine(Tx tx, long userId, long itemId, int quantity) throws SQLException {
        if (quantity > 0) {
            tx.update("INSERT INTO cart_items (user_id, item_id, quantity) VALUES (?, ?, ?)"
                    + " ON CONFLICT (user_id, item_id) DO UPDATE SET quantity = excluded.quantity", userId, itemId, quantity);
        } else {
            tx.update("DELETE FROM cart_items WHERE user_id = ? AND item_id = ?", userId, itemId);
        }
    }

    /** L'objet s'il est visible et réservable par l'utilisateur. */
    private static Optional<Item> reservableItem(Tx tx, User user, long itemId) throws SQLException {
        List<Item> found = Visibility.visibleItems(tx, user, CategoryTree.load(tx), null, List.of(itemId));
        return found.stream().filter(i -> i.reservable && i.available > 0).findFirst();
    }

    /** Crée une demande « en attente » à partir du panier, après avoir revérifié la disponibilité. */
    public long submitCart(User user) {
        return db.tx(tx -> {
            Map<Long, Integer> quantities = cartQuantities(tx, user.id);
            if (quantities.isEmpty()) {
                throw ApiError.invalid("Votre panier est vide.");
            }
            Map<Long, String> names = new HashMap<>();
            for (Item item : tx.list("SELECT * FROM items WHERE id IN (" + Tx.placeholders(quantities.keySet()) + ")",
                    Item::map, quantities.keySet())) {
                names.put(item.id, item.name);
            }
            Map<Long, Item> available = indexById(Visibility.visibleItems(tx, user, CategoryTree.load(tx), null, quantities.keySet()));

            List<String> errors = new ArrayList<>();
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                Item item = available.get(entry.getKey());
                String name = names.getOrDefault(entry.getKey(), "Un article");
                if (item == null || item.available == 0) {
                    errors.add(name + " n'est plus disponible : retirez-le du panier.");
                } else if (!item.reservable) {
                    errors.add(name + " est un consommable et ne se réserve pas.");
                } else if (entry.getValue() > item.available) {
                    errors.add(name + " : il n'en reste que " + Text.plural(item.available, "disponible") + ".");
                }
            }
            if (!errors.isEmpty()) {
                throw ApiError.invalid(errors);
            }

            String now = Instant.now().toString();
            long requestId = tx.insert("INSERT INTO requests (requester_id, created_at, updated_at) VALUES (?, ?, ?)", user.id, now, now);
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                tx.update("INSERT INTO request_lines (request_id, item_id, quantity) VALUES (?, ?, ?)",
                        requestId, entry.getKey(), entry.getValue());
            }
            tx.update("DELETE FROM cart_items WHERE user_id = ?", user.id);
            return requestId;
        });
    }

    // Décisions de l'admin ------------------------------------------------------------------

    /** Décision sur une ligne : action vide = rien à faire ; comment null = inchangé. */
    public static class Decision {
        public long line;
        public String action;
        public Integer quantity;
        public String comment;

        public Decision() {
        }

        public Decision(long line, String action, Integer quantity, String comment) {
            this.line = line;
            this.action = action;
            this.quantity = quantity;
            this.comment = comment;
        }
    }

    /** Raccourcis de la page de traitement. */
    public static final String RESERVE_ALL = "reserve_all";
    public static final String RETURN_ALL = "return_all";

    /**
     * Applique les décisions de l'admin sur une demande, tout-ou-rien.
     * bulk = reserve_all / return_all remplace les décisions ligne par ligne.
     * Renvoie le nombre de lignes modifiées.
     */
    public int applyDecisions(long requestId, String bulk, List<Decision> decisions) {
        return db.tx(tx -> {
            LoanRequest request = loadRequest(tx, requestId).orElseThrow(() -> ApiError.notFound("Demande introuvable."));
            Map<Long, LoanRequest.Line> lines = request.lines.stream().collect(Collectors.toMap(l -> l.id, Function.identity()));

            List<Decision> todo = new ArrayList<>();
            if (RESERVE_ALL.equals(bulk)) {
                request.lines.stream().filter(l -> l.status == LineStatus.PENDING)
                        .forEach(l -> todo.add(new Decision(l.id, RESERVE, l.quantity, null)));
            } else if (RETURN_ALL.equals(bulk)) {
                request.lines.stream().filter(l -> l.status == LineStatus.RESERVED)
                        .forEach(l -> todo.add(new Decision(l.id, RETURN, null, null)));
            } else if (decisions != null) {
                todo.addAll(decisions);
            }

            Map<Long, Integer> reserved = Availability.reservedQuantities(tx,
                    request.lines.stream().map(l -> l.itemId).collect(Collectors.toList()));
            String now = Instant.now().toString();
            List<String> errors = new ArrayList<>();
            int changed = 0;

            for (Decision decision : todo) {
                LoanRequest.Line line = lines.get(decision.line);
                if (line == null) {
                    errors.add("Ligne " + decision.line + " introuvable dans cette demande.");
                    continue;
                }
                String comment = decision.comment == null ? null : decision.comment.trim();
                if (comment != null && comment.length() > 200) {
                    errors.add(line.itemName + " : le commentaire ne doit pas dépasser 200 caractères.");
                    continue;
                }
                if (decision.action == null || decision.action.isEmpty()) {
                    // Pas d'action : seul le commentaire peut changer
                    if (comment != null && !comment.equals(line.adminComment)) {
                        tx.update("UPDATE request_lines SET admin_comment = ? WHERE id = ?", comment, line.id);
                        changed++;
                    }
                    continue;
                }
                if (ALLOWED_FROM.get(decision.action) != line.status) {
                    errors.add(line.itemName + " : action impossible (statut « " + line.status.label + " »).");
                    continue;
                }
                String newComment = comment == null ? line.adminComment : comment;
                switch (decision.action) {
                    case RESERVE: {
                        int quantity = decision.quantity == null ? line.quantity : decision.quantity;
                        int free = line.itemQuantity - reserved.getOrDefault(line.itemId, 0);
                        if (quantity < 1 || quantity > line.quantity) {
                            errors.add(line.itemName + " : la quantité doit être entre 1 et " + line.quantity + ".");
                            continue;
                        }
                        if (quantity > free) {
                            errors.add(line.itemName + " : seulement " + Text.plural(Math.max(free, 0), "disponible") + ".");
                            continue;
                        }
                        reserved.merge(line.itemId, quantity, Integer::sum);
                        tx.update("UPDATE request_lines SET quantity = ?, status = ?, reserved_at = ?, admin_comment = ? WHERE id = ?",
                                quantity, LineStatus.RESERVED.code, now, newComment, line.id);
                        break;
                    }
                    case REFUSE:
                        tx.update("UPDATE request_lines SET status = ?, admin_comment = ? WHERE id = ?",
                                LineStatus.REFUSED.code, newComment, line.id);
                        break;
                    case RETURN:
                        tx.update("UPDATE request_lines SET status = ?, returned_at = ?, admin_comment = ? WHERE id = ?",
                                LineStatus.RETURNED.code, now, newComment, line.id);
                        break;
                    default: // UNRESERVE
                        reserved.merge(line.itemId, -line.quantity, Integer::sum);
                        tx.update("UPDATE request_lines SET status = ?, reserved_at = NULL, admin_comment = ? WHERE id = ?",
                                LineStatus.PENDING.code, newComment, line.id);
                }
                changed++;
            }
            if (!errors.isEmpty()) {
                // Annule la transaction : rien n'est enregistré
                throw ApiError.invalid(errors);
            }
            if (changed > 0) {
                tx.update("UPDATE requests SET updated_at = ? WHERE id = ?", now, requestId);
            }
            return changed;
        });
    }

    /** L'invité annule les articles encore en attente de sa demande. Renvoie les lignes annulées. */
    public List<LoanRequest.Line> cancelRequest(long requestId, User user) {
        return db.tx(tx -> {
            LoanRequest request = loadRequest(tx, requestId).orElseThrow(() -> ApiError.notFound("Demande introuvable."));
            if (request.requesterId != user.id) {
                throw ApiError.notFound("Demande introuvable.");
            }
            List<LoanRequest.Line> pending = request.lines.stream()
                    .filter(l -> l.status == LineStatus.PENDING).collect(Collectors.toList());
            if (pending.isEmpty()) {
                throw ApiError.invalid("Il n'y a plus d'article en attente à annuler.");
            }
            for (LoanRequest.Line line : pending) {
                tx.update("UPDATE request_lines SET status = ? WHERE id = ?", LineStatus.CANCELLED.code, line.id);
                line.status = LineStatus.CANCELLED;
            }
            tx.update("UPDATE requests SET updated_at = ? WHERE id = ?", Instant.now().toString(), requestId);
            return pending;
        });
    }

    // Lecture -----------------------------------------------------------------------------

    private static final String REQUEST_SQL =
            "SELECT r.id, r.requester_id, r.created_at, u.username, u.first_name, u.last_name, u.email"
                    + " FROM requests r JOIN users u ON u.id = r.requester_id";

    public Optional<LoanRequest> findRequest(long id) {
        return db.read(tx -> loadRequest(tx, id));
    }

    /** Demande visible par l'utilisateur : la sienne, ou n'importe laquelle pour l'admin. */
    public LoanRequest requestFor(User user, long id, boolean adminView) {
        LoanRequest request = findRequest(id).orElseThrow(() -> ApiError.notFound("Demande introuvable."));
        if (adminView) {
            Roles.requireAdmin(user);
        } else if (request.requesterId != user.id) {
            throw ApiError.notFound("Demande introuvable.");
        }
        if (!adminView) {
            request.lines.forEach(l -> {
                l.itemAvailable = null;
                l.itemQuantity = null;
            });
        }
        return request;
    }

    public List<LoanRequest> myRequests(User user) {
        return db.read(tx -> loadRequests(tx, " WHERE r.requester_id = ?", user.id));
    }

    /** Onglets de la gestion : a-traiter (lignes en attente), en-cours (lignes réservées), toutes. */
    public List<LoanRequest> manageList(String tab) {
        return db.read(tx -> {
            switch (tab == null ? "" : tab) {
                case "en-cours":
                    return loadRequests(tx, " WHERE r.id IN (SELECT request_id FROM request_lines WHERE status = ?)", LineStatus.RESERVED.code);
                case "toutes":
                    return loadRequests(tx, "");
                default:
                    return loadRequests(tx, " WHERE r.id IN (SELECT request_id FROM request_lines WHERE status = ?)", LineStatus.PENDING.code);
            }
        });
    }

    /** Nombre de demandes ayant au moins un article en attente (badge du menu admin). */
    public long pendingRequestCount() {
        return db.read(tx -> tx.count("SELECT COUNT(DISTINCT request_id) FROM request_lines WHERE status = ?", LineStatus.PENDING.code));
    }

    private Optional<LoanRequest> loadRequest(Tx tx, long id) throws SQLException {
        List<LoanRequest> found = loadRequests(tx, " WHERE r.id = ?", id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        // Disponibilité actuelle de chaque objet (y compris inactifs), pour aider la décision
        LoanRequest request = found.get(0);
        Map<Long, Integer> reserved = Availability.reservedQuantities(tx,
                request.lines.stream().map(l -> l.itemId).collect(Collectors.toList()));
        for (LoanRequest.Line line : request.lines) {
            line.itemAvailable = Math.max(line.itemQuantity - reserved.getOrDefault(line.itemId, 0), 0);
        }
        return Optional.of(request);
    }

    /** Demandes (les plus récentes d'abord) avec leurs lignes, en deux requêtes. */
    private List<LoanRequest> loadRequests(Tx tx, String where, Object... params) throws SQLException {
        List<LoanRequest> requests = tx.list(REQUEST_SQL + where + " ORDER BY r.created_at DESC, r.id DESC", rs -> {
            LoanRequest r = new LoanRequest();
            r.id = rs.getLong("id");
            r.requesterId = rs.getLong("requester_id");
            r.createdAt = rs.getString("created_at");
            String full = (rs.getString("first_name") + " " + rs.getString("last_name")).trim();
            r.requesterName = full.isEmpty() ? rs.getString("username") : full;
            r.requesterEmail = rs.getString("email");
            return r;
        }, params);
        if (requests.isEmpty()) {
            return requests;
        }
        Map<Long, LoanRequest> byId = requests.stream().collect(Collectors.toMap(r -> r.id, Function.identity()));
        tx.list("SELECT l.*, i.name AS item_name, i.reference AS item_reference, i.location AS item_location,"
                        + " i.quantity AS item_quantity FROM request_lines l JOIN items i ON i.id = l.item_id"
                        + " WHERE l.request_id IN (" + Tx.placeholders(byId.keySet()) + ") ORDER BY i.name COLLATE NOCASE",
                rs -> {
                    LoanRequest.Line line = new LoanRequest.Line();
                    line.id = rs.getLong("id");
                    line.itemId = rs.getLong("item_id");
                    line.itemName = rs.getString("item_name");
                    line.itemReference = rs.getString("item_reference");
                    line.itemLocation = rs.getString("item_location");
                    line.itemQuantity = rs.getInt("item_quantity");
                    line.quantity = rs.getInt("quantity");
                    line.status = LineStatus.of(rs.getString("status"));
                    line.adminComment = rs.getString("admin_comment");
                    line.reservedAt = rs.getString("reserved_at");
                    line.returnedAt = rs.getString("returned_at");
                    byId.get(rs.getLong("request_id")).lines.add(line);
                    return line;
                }, byId.keySet());
        return requests;
    }

    private static Map<Long, Item> indexById(List<Item> items) {
        return items.stream().collect(Collectors.toMap(i -> i.id, Function.identity()));
    }
}
