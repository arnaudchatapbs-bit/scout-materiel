package scout.materiel.loans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scout.materiel.Fixtures;
import scout.materiel.accounts.User;
import scout.materiel.common.ApiError;
import scout.materiel.inventory.InventoryService;
import scout.materiel.inventory.Item;

class LoanServiceTest {
    Fixtures f;
    LoanService loans;
    InventoryService inventory;
    User admin;
    User guest;
    long cuisine;
    long economes;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        loans = f.services.loans;
        inventory = f.services.inventory;
        admin = f.admin();
        guest = f.guest();
        cuisine = f.category("Cuisine", null, false);
        economes = f.item("Économes", cuisine, 5);
    }

    /** Demande de l'invité pour qty économes, puis l'admin en réserve reserve. */
    long requestAndReserve(int qty, int reserve) {
        loans.addToCart(guest, economes, qty);
        long id = loans.submitCart(guest);
        long line = loans.findRequest(id).orElseThrow().lines.get(0).id;
        loans.applyDecisions(id, null, List.of(new LoanService.Decision(line, LoanService.RESERVE, reserve, null)));
        return id;
    }

    Item seenBy(User user, long itemId) {
        return inventory.itemPage(user, itemId, Map.of()).item;
    }

    @Test
    void pendingLinesDoNotBlockAnything() {
        loans.addToCart(guest, economes, 2);
        loans.submitCart(guest);
        Item item = seenBy(admin, economes);
        assertEquals(5, item.available);
        assertEquals(2, item.pending);
        assertEquals(null, seenBy(guest, economes).pending, "les invités ne voient pas les demandes en attente");
    }

    @Test
    void partialReservationLeavesTheRestVisible() {
        requestAndReserve(2, 2);
        assertEquals(3, seenBy(guest, economes).available);
    }

    @Test
    void reserveFewerThanRequested() {
        long id = requestAndReserve(4, 1);
        LoanRequest.Line line = loans.findRequest(id).orElseThrow().lines.get(0);
        assertEquals(1, line.quantity);
        assertEquals(LineStatus.RESERVED, line.status);
        assertEquals(4, seenBy(guest, economes).available);
    }

    @Test
    void fullyReservedItemIsInvisibleToGuestsUntilReturned() {
        long id = requestAndReserve(5, 5);
        ApiError error = assertThrows(ApiError.class, () -> seenBy(guest, economes));
        assertEquals(404, error.status);
        assertTrue(inventory.listPage(guest, cuisine, null, Map.of()).groups.isEmpty());
        assertTrue(inventory.listPage(guest, null, null, Map.of()).families.isEmpty(), "famille vide masquée");
        assertEquals(0, seenBy(admin, economes).available, "l'admin voit toujours l'objet");

        loans.applyDecisions(id, LoanService.RETURN_ALL, null);
        assertEquals(5, seenBy(guest, economes).available);
    }

    @Test
    void cannotAddMoreThanAvailable() {
        requestAndReserve(3, 3);
        LoanService.CartChange change = loans.addToCart(guest, economes, 10);
        assertTrue(change.limited);
        assertEquals(2, loans.cart(guest).items.get(0).inCart);
    }

    @Test
    void availabilityIsCheckedAgainAtSubmit() {
        User other = f.user("autre", false);
        loans.addToCart(guest, economes, 4);
        requestAndReserveFor(other, 3);
        ApiError error = assertThrows(ApiError.class, () -> loans.submitCart(guest));
        assertEquals(List.of("Économes : il n'en reste que 2 disponibles."), error.messages);
        assertEquals(1, loans.cartCount(guest.id), "le panier est conservé");
    }

    void requestAndReserveFor(User user, int qty) {
        loans.addToCart(user, economes, qty);
        long id = loans.submitCart(user);
        loans.applyDecisions(id, LoanService.RESERVE_ALL, null);
    }

    @Test
    void consumablesAreVisibleButNotReservable() {
        long food = f.category("Alimentation", null, true);
        long sugar = f.item("Sucre", f.category("Épicerie", food, false), 10);
        Item item = seenBy(guest, sugar);
        assertFalse(item.reservable);
        assertEquals(0, item.addable);
        assertThrows(ApiError.class, () -> loans.addToCart(guest, sugar, 1));
    }

    @Test
    void decisionsAreAllOrNothing() {
        long tents = f.item("Tente", cuisine, 1);
        loans.addToCart(guest, economes, 2);
        loans.addToCart(guest, tents, 1);
        long id = loans.submitCart(guest);
        // Quelqu'un d'autre a eu la tente entre-temps
        User other = f.user("autre", false);
        loans.addToCart(other, tents, 1);
        long otherId = loans.submitCart(other);
        loans.applyDecisions(otherId, LoanService.RESERVE_ALL, null);

        ApiError error = assertThrows(ApiError.class, () -> loans.applyDecisions(id, LoanService.RESERVE_ALL, null));
        assertEquals(List.of("Tente : seulement 0 disponible."), error.messages);
        assertTrue(loans.findRequest(id).orElseThrow().lines.stream().allMatch(l -> l.status == LineStatus.PENDING),
                "les économes ne sont pas réservés non plus");
    }

    @Test
    void actionMustMatchCurrentStatus() {
        long id = requestAndReserve(1, 1);
        long line = loans.findRequest(id).orElseThrow().lines.get(0).id;
        ApiError error = assertThrows(ApiError.class, () -> loans.applyDecisions(id, null,
                List.of(new LoanService.Decision(line, LoanService.REFUSE, null, null))));
        assertTrue(error.messages.get(0).contains("action impossible"));
    }

    @Test
    void unreserveGoesBackToPending() {
        long id = requestAndReserve(2, 2);
        long line = loans.findRequest(id).orElseThrow().lines.get(0).id;
        loans.applyDecisions(id, null, List.of(new LoanService.Decision(line, LoanService.UNRESERVE, null, "plus tard")));
        LoanRequest.Line updated = loans.findRequest(id).orElseThrow().lines.get(0);
        assertEquals(LineStatus.PENDING, updated.status);
        assertEquals("plus tard", updated.adminComment);
        assertEquals(5, seenBy(guest, economes).available);
    }

    @Test
    void requestStatusIsDerivedFromLines() {
        long tents = f.item("Tente", cuisine, 1);
        loans.addToCart(guest, economes, 1);
        loans.addToCart(guest, tents, 1);
        long id = loans.submitCart(guest);
        assertEquals("en_attente", loans.findRequest(id).orElseThrow().getStatus());
        loans.applyDecisions(id, LoanService.RESERVE_ALL, null);
        assertEquals("reservee", loans.findRequest(id).orElseThrow().getStatus());
        loans.applyDecisions(id, LoanService.RETURN_ALL, null);
        assertEquals("terminee", loans.findRequest(id).orElseThrow().getStatus());
    }

    @Test
    void guestCancelsOnlyPendingLinesOfOwnRequest() {
        long id = requestAndReserve(1, 1);
        assertThrows(ApiError.class, () -> loans.cancelRequest(id, guest), "plus rien en attente");

        loans.addToCart(guest, economes, 1);
        long second = loans.submitCart(guest);
        assertThrows(ApiError.class, () -> loans.cancelRequest(second, f.user("autre", false)));
        assertEquals(1, loans.cancelRequest(second, guest).size());
        assertEquals("annulee", loans.findRequest(second).orElseThrow().getStatus());
    }

    @Test
    void manageTabs() {
        long reserved = requestAndReserve(1, 1);
        loans.addToCart(guest, economes, 1);
        long pending = loans.submitCart(guest);
        assertEquals(List.of(pending), ids(loans.manageList("a-traiter")));
        assertEquals(List.of(reserved), ids(loans.manageList("en-cours")));
        assertEquals(2, loans.manageList("toutes").size());
        assertEquals(1, loans.pendingRequestCount());
    }

    @Test
    void guestCannotSeeSomeoneElsesRequest() {
        loans.addToCart(guest, economes, 1);
        long id = loans.submitCart(guest);
        User other = f.user("autre", false);
        assertEquals(404, assertThrows(ApiError.class, () -> loans.requestFor(other, id, false)).status);
        assertEquals(403, assertThrows(ApiError.class, () -> loans.requestFor(guest, id, true)).status);
    }

    @Test
    void notificationsGoToAdminAndGuest() {
        loans.addToCart(guest, economes, 2);
        long id = loans.submitCart(guest);
        assertTrue(f.services.notifications.newRequest(loans.findRequest(id).orElseThrow()));
        assertEquals(2, f.services.mailer.outbox.size());
        assertEquals(List.of("chef@example.org"), f.services.mailer.outbox.get(0).to);
        assertTrue(f.services.mailer.outbox.get(0).body.contains("http://test.local/gestion/demandes/" + id));
        assertEquals(List.of("invite@example.org"), f.services.mailer.outbox.get(1).to);
    }

    @Test
    void notificationRecipientsSetInManagementWinOverDefaults() {
        assertEquals(List.of("chef@example.org"), f.services.notifications.adminRecipients(), "par défaut : les comptes admin");
        f.services.notifications.saveRecipients(" materiel@scouts.fr, Tresorier@scouts.fr ;tresorier@SCOUTS.fr,");
        assertEquals(List.of("materiel@scouts.fr", "Tresorier@scouts.fr"), f.services.notifications.adminRecipients());

        loans.addToCart(guest, economes, 1);
        long id = loans.submitCart(guest);
        f.services.notifications.newRequest(loans.findRequest(id).orElseThrow());
        assertEquals(List.of("materiel@scouts.fr", "Tresorier@scouts.fr"), f.services.mailer.outbox.get(0).to);

        ApiError error = assertThrows(ApiError.class, () -> f.services.notifications.saveRecipients("ok@scouts.fr, pas-une-adresse"));
        assertEquals("Adresse e-mail invalide : pas-une-adresse", error.messages.get(0));
        assertEquals(2, f.services.notifications.adminRecipients().size(), "rien enregistré en cas d'erreur");

        f.services.notifications.saveRecipients("");
        assertEquals(List.of("chef@example.org"), f.services.notifications.adminRecipients(), "vide : retour au défaut");
    }

    @Test
    void envRecipientsUsedWhenNothingSetInManagement() {
        Fixtures withEnv = new Fixtures(Map.of("ADMIN_EMAILS", "env@scouts.fr"));
        assertEquals(List.of("env@scouts.fr"), withEnv.services.notifications.adminRecipients());
        assertEquals("env", withEnv.services.notifications.recipientsSettings().get("source"));
        withEnv.services.notifications.saveRecipients("ecran@scouts.fr");
        assertEquals(List.of("ecran@scouts.fr"), withEnv.services.notifications.adminRecipients());
    }

    private static List<Long> ids(List<LoanRequest> requests) {
        return requests.stream().map(r -> r.id).collect(java.util.stream.Collectors.toList());
    }
}
