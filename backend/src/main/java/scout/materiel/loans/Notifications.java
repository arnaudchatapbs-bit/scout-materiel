package scout.materiel.loans;

import java.util.List;
import java.util.stream.Collectors;
import scout.materiel.Config;
import scout.materiel.accounts.AccountService;
import scout.materiel.accounts.Roles;
import scout.materiel.mail.Mailer;

/**
 * E-mails liés aux demandes. À appeler après l'enregistrement : une panne SMTP ne doit jamais
 * empêcher une demande d'être enregistrée. Chaque méthode renvoie true si tous les mails sont partis.
 *
 * Aucun mail à l'invité pour les décisions de l'admin : il consulte « Mes demandes ».
 */
public class Notifications {
    private final Config config;
    private final Mailer mailer;
    private final AccountService accounts;

    public Notifications(Config config, Mailer mailer, AccountService accounts) {
        this.config = config;
        this.mailer = mailer;
        this.accounts = accounts;
    }

    /** ADMIN_EMAILS du .env, sinon les e-mails des comptes administrateurs actifs. */
    public List<String> adminRecipients() {
        if (!config.adminEmails.isEmpty()) {
            return config.adminEmails;
        }
        return accounts.listUsers().stream().filter(Roles::isAdmin).map(u -> u.email)
                .filter(e -> !e.isEmpty()).collect(Collectors.toList());
    }

    /** Nouvelle demande : mail à l'admin (à traiter) et confirmation à l'invité. */
    public boolean newRequest(LoanRequest request) {
        boolean toAdmin = mailer.send(
                "Nouvelle demande de matériel n°" + request.id + " — " + request.requesterName,
                "Bonjour,\n\n" + request.requesterName + " (" + request.requesterEmail + ") vient d'envoyer une demande de matériel (n°"
                        + request.id + ") :\n\n" + linesText(request.lines, true)
                        + "\nPour réserver ou refuser les articles :\n" + config.siteUrl + "/gestion/demandes/" + request.id
                        + "\n\n— Scout Matériel\n",
                adminRecipients());
        String firstName = accounts.findUser(request.requesterId).map(u -> u.firstName.isEmpty() ? u.username : u.firstName)
                .orElse(request.requesterName);
        boolean toGuest = mailer.send(
                "Votre demande de matériel n°" + request.id + " est bien reçue",
                "Bonjour " + firstName + ",\n\nVotre demande de matériel n°" + request.id + " est bien reçue :\n\n"
                        + linesText(request.lines, false)
                        + "\nL'administrateur va réserver le matériel. Vous pouvez suivre l'état de chaque article ici :\n"
                        + config.siteUrl + "/demandes/" + request.id + "\n\n— Scout Matériel\n",
                request.requesterEmail == null || request.requesterEmail.isEmpty() ? List.of() : List.of(request.requesterEmail));
        return toAdmin && toGuest;
    }

    /** L'invité a annulé des articles en attente : mail à l'admin. */
    public boolean cancelled(LoanRequest request, List<LoanRequest.Line> cancelledLines) {
        return mailer.send(
                "Demande n°" + request.id + " annulée par " + request.requesterName,
                "Bonjour,\n\n" + request.requesterName + " a annulé les articles suivants de la demande n°" + request.id + " :\n\n"
                        + linesText(cancelledLines, false)
                        + "\nDétail de la demande :\n" + config.siteUrl + "/gestion/demandes/" + request.id + "\n\n— Scout Matériel\n",
                adminRecipients());
    }

    private static String linesText(List<LoanRequest.Line> lines, boolean withLocation) {
        StringBuilder text = new StringBuilder();
        for (LoanRequest.Line line : lines) {
            text.append("- ").append(line.itemName);
            if (line.itemReference != null) {
                text.append(" [").append(line.itemReference).append("]");
            }
            text.append(" × ").append(line.quantity);
            if (withLocation && line.itemLocation != null && !line.itemLocation.isEmpty()) {
                text.append(" — ").append(line.itemLocation);
            }
            text.append("\n");
        }
        return text.toString();
    }
}
