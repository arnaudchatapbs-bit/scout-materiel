package scout.materiel.accounts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scout.materiel.Fixtures;
import scout.materiel.common.ApiError;

class AccountServiceTest {
    Fixtures f;
    AccountService accounts;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        accounts = f.services.accounts;
    }

    @Test
    void pbkdf2MatchesReferenceVector() {
        // RFC 7914 § 11 : PBKDF2-HMAC-SHA256("passwd", "salt", 1), 32 premiers octets
        byte[] expected = hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc");
        assertEquals("pbkdf2_sha256$1$salt$" + Base64.getEncoder().encodeToString(expected), PasswordHasher.encode("passwd", "salt", 1));
    }

    @Test
    void acceptsDjangoHashesAndRehashesThem() {
        User user = f.guest();
        String django = PasswordHasher.encode("ancien-mot-de-passe-django", "seldjango", 870_000 / 870);
        f.services.db.tx(tx -> tx.update("UPDATE users SET password_hash = ? WHERE id = ?", django, user.id));
        User logged = accounts.authenticate("invite", "ancien-mot-de-passe-django");
        assertTrue(logged.passwordHash.startsWith("pbkdf2_sha256$1000$"), "ré-encodé avec PASSWORD_ITERATIONS");
    }

    @Test
    void loginWithUsernameOrEmailIgnoringCase() {
        f.guest();
        assertEquals("invite", accounts.authenticate("INVITE", Fixtures.PASSWORD).username);
        assertEquals("invite", accounts.authenticate("Invite@Example.org", Fixtures.PASSWORD).username);
        assertThrows(ApiError.class, () -> accounts.authenticate("invite", "mauvais"));
        assertThrows(ApiError.class, () -> accounts.authenticate("inconnu", Fixtures.PASSWORD));
    }

    @Test
    void inactiveAccountCannotLogIn() {
        User user = f.guest();
        f.services.db.tx(tx -> tx.update("UPDATE users SET is_active = 0 WHERE id = ?", user.id));
        assertThrows(ApiError.class, () -> accounts.authenticate("invite", Fixtures.PASSWORD));
    }

    @Test
    void repeatedFailuresLockTheLogin() {
        f.guest();
        for (int i = 0; i < 5; i++) {
            assertThrows(ApiError.class, () -> accounts.authenticate("invite", "mauvais"));
        }
        ApiError error = assertThrows(ApiError.class, () -> accounts.authenticate("invite", Fixtures.PASSWORD));
        assertEquals(429, error.status);
    }

    @Test
    void sessions() {
        User user = f.guest();
        String token = accounts.createSession(user.id);
        assertEquals(user.id, accounts.sessionUser(token).orElseThrow().id);
        accounts.deleteSession(token);
        assertFalse(accounts.sessionUser(token).isPresent());
        assertFalse(accounts.sessionUser("n'importe-quoi").isPresent());
    }

    @Test
    void invitationLinkSetsPasswordOnce() {
        AccountService.UserForm form = new AccountService.UserForm();
        form.username = "louveteau";
        form.email = "louveteau@example.org";
        User user = accounts.createUser(form);
        assertFalse(user.hasPassword());
        assertTrue(accounts.sendInvitation(user));
        String body = f.services.mailer.outbox.get(0).body;
        String token = body.substring(body.indexOf("jeton=") + 6).split("\\s")[0];

        accounts.resetPassword(token, "mon-nouveau-mot-de-passe");
        assertEquals("louveteau", accounts.authenticate("louveteau", "mon-nouveau-mot-de-passe").username);
        assertThrows(ApiError.class, () -> accounts.resetPassword(token, "encore-un-autre-mot-de-passe"), "lien à usage unique");
    }

    @Test
    void passwordRules() {
        User user = f.guest();
        assertThrows(ApiError.class, () -> accounts.changePassword(user, Fixtures.PASSWORD, "court"));
        assertThrows(ApiError.class, () -> accounts.changePassword(user, Fixtures.PASSWORD, "123456789012345"));
        assertThrows(ApiError.class, () -> accounts.changePassword(user, "mauvais-mot-de-passe", "un-autre-tres-long"));
        accounts.changePassword(user, Fixtures.PASSWORD, "un-autre-tres-long");
        assertEquals(user.id, accounts.authenticate("invite", "un-autre-tres-long").id);
    }

    @Test
    void emailIsRequiredAndUnique() {
        f.guest();
        AccountService.UserForm form = new AccountService.UserForm();
        form.username = "nouveau";
        form.email = "INVITE@example.org";
        ApiError error = assertThrows(ApiError.class, () -> accounts.createUser(form));
        assertTrue(error.messages.get(0).contains("déjà utilisée"));
        form.email = "";
        assertThrows(ApiError.class, () -> accounts.createUser(form));
    }

    @Test
    void adminCannotDemoteOrDeactivateThemself() {
        User admin = f.admin();
        AccountService.UserForm form = new AccountService.UserForm();
        form.username = admin.username;
        form.email = admin.email;
        form.admin = true;
        form.active = false;
        assertThrows(ApiError.class, () -> accounts.updateUser(admin.id, form, admin));
    }

    @Test
    void roleIsAdminFlagOnActiveAccount() {
        User admin = f.admin();
        assertTrue(Roles.isAdmin(admin));
        admin.active = false;
        assertFalse(Roles.isAdmin(admin));
        assertFalse(Roles.isAdmin(f.guest()));
        assertFalse(Roles.isAdmin(null));
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
