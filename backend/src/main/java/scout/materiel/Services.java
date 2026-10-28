package scout.materiel;

import scout.materiel.accounts.AccountService;
import scout.materiel.db.Database;
import scout.materiel.inventory.InventoryService;
import scout.materiel.loans.LoanService;
import scout.materiel.loans.Notifications;
import scout.materiel.mail.Mailer;

/** Assemblage des services (pas de conteneur d'injection : démarrage plus rapide sur le Pi). */
public final class Services implements AutoCloseable {
    public final Config config;
    public final Database db;
    public final Mailer mailer;
    public final AccountService accounts;
    public final InventoryService inventory;
    public final LoanService loans;
    public final Notifications notifications;

    public Services(Config config, Database db) {
        this.config = config;
        this.db = db;
        this.mailer = new Mailer(config);
        this.accounts = new AccountService(db, config, mailer);
        this.inventory = new InventoryService(db);
        this.loans = new LoanService(db);
        this.notifications = new Notifications(config, mailer, accounts);
    }

    public static Services open(Config config) {
        return new Services(config, Database.open(config.databasePath));
    }

    @Override
    public void close() {
        db.close();
    }
}
