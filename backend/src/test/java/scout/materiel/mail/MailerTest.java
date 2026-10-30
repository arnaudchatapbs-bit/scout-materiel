package scout.materiel.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;

/** En-tête From : doit rester ASCII (Yahoo refuse les en-têtes contenant des accents bruts). */
class MailerTest {
    @Test
    void accentedNameIsEncoded() throws Exception {
        assertEquals("=?UTF-8?Q?Scout_Mat=C3=A9riel?= <adresse@gmail.com>",
                Mailer.fromAddress("Scout Matériel <adresse@gmail.com>").toString());
    }

    @Test
    void bareAddressIsKept() throws Exception {
        assertEquals("noreply@example.org", Mailer.fromAddress("noreply@example.org").toString());
    }

    @Test
    void invalidAddressIsReported() {
        MessagingException e = assertThrows(MessagingException.class, () -> Mailer.fromAddress("Scout <pas une adresse"));
        assertEquals("DEFAULT_FROM_EMAIL invalide : Scout <pas une adresse", e.getMessage());
    }
}
