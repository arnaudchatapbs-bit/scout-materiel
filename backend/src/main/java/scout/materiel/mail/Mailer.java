package scout.materiel.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scout.materiel.Config;

/**
 * Envoi des e-mails : « console » (développement, affichés dans le journal) ou « smtp ».
 *
 * send() ne lève jamais d'exception : une panne SMTP ne doit pas bloquer l'enregistrement.
 * Il renvoie false (et journalise l'erreur) pour que l'utilisateur soit prévenu.
 */
public class Mailer {
    private static final Logger LOG = LoggerFactory.getLogger(Mailer.class);

    private final Config config;
    /** Mails « envoyés » par le backend memory (tests). */
    public final List<Sent> outbox = new ArrayList<>();

    public Mailer(Config config) {
        this.config = config;
    }

    public boolean send(String subject, String body, List<String> recipients) {
        if (recipients.isEmpty()) {
            LOG.warn("Aucun destinataire pour « {} »", subject);
            return false;
        }
        try {
            deliver(subject, body, recipients);
            return true;
        } catch (Exception e) {
            LOG.error("Échec de l'envoi du mail « {} » à {}", subject, recipients, e);
            return false;
        }
    }

    /** Comme send(), mais lève l'erreur (commande test-email). */
    public void deliver(String subject, String body, List<String> recipients) throws MessagingException {
        switch (config.emailBackend) {
            case "memory":
                outbox.add(new Sent(subject, body, List.copyOf(recipients)));
                return;
            case "console":
                LOG.info("\n--- E-mail (console) ---\nDe : {}\nÀ : {}\nObjet : {}\n\n{}\n------------------------",
                        config.fromEmail, String.join(", ", recipients), subject, body);
                return;
            case "smtp":
                sendSmtp(subject, body, recipients);
                return;
            default:
                throw new MessagingException("EMAIL_BACKEND inconnu : " + config.emailBackend);
        }
    }

    /** Mail avec une pièce jointe (sauvegarde de la base) ; lève l'erreur en cas d'échec. */
    public void deliverWithAttachment(String subject, String body, List<String> recipients, java.nio.file.Path file)
            throws MessagingException {
        if (!"smtp".equals(config.emailBackend)) {
            deliver(subject, body + "\n[pièce jointe : " + file.getFileName() + "]", recipients);
            return;
        }
        jakarta.mail.internet.MimeBodyPart text = new jakarta.mail.internet.MimeBodyPart();
        text.setText(body, StandardCharsets.UTF_8.name());
        jakarta.mail.internet.MimeBodyPart attachment = new jakarta.mail.internet.MimeBodyPart();
        try {
            attachment.attachFile(file.toFile(), "application/gzip", null);
        } catch (java.io.IOException e) {
            throw new MessagingException("Pièce jointe illisible : " + file, e);
        }
        jakarta.mail.internet.MimeMultipart multipart = new jakarta.mail.internet.MimeMultipart(text, attachment);
        sendSmtp(subject, multipart, recipients);
    }

    private void sendSmtp(String subject, String body, List<String> recipients) throws MessagingException {
        sendSmtp(subject, (Object) body, recipients);
    }

    private void sendSmtp(String subject, Object content, List<String> recipients) throws MessagingException {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.emailHost);
        props.put("mail.smtp.port", String.valueOf(config.emailPort));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.emailUseTls));
        props.put("mail.smtp.starttls.required", String.valueOf(config.emailUseTls));
        props.put("mail.smtp.auth", String.valueOf(!config.emailUser.isEmpty()));
        // Ne jamais bloquer une requête longtemps si le serveur ne répond pas
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");

        Session session = Session.getInstance(props);
        MimeMessage message = new MimeMessage(session);
        try {
            message.setFrom(new InternetAddress(config.fromEmail, false));
        } catch (MessagingException e) {
            throw new MessagingException("DEFAULT_FROM_EMAIL invalide : " + config.fromEmail, e);
        }
        for (String recipient : recipients) {
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
        }
        message.setSubject(subject, StandardCharsets.UTF_8.name());
        if (content instanceof jakarta.mail.Multipart) {
            message.setContent((jakarta.mail.Multipart) content);
        } else {
            message.setText((String) content, StandardCharsets.UTF_8.name());
        }
        if (config.emailUser.isEmpty()) {
            Transport.send(message);
        } else {
            Transport.send(message, config.emailUser, config.emailPassword);
        }
    }

    public static final class Sent {
        public final String subject;
        public final String body;
        public final List<String> to;

        Sent(String subject, String body, List<String> to) {
            this.subject = subject;
            this.body = body;
            this.to = to;
        }
    }
}
