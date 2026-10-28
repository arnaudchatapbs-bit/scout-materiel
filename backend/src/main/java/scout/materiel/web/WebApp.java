package scout.materiel.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scout.materiel.Services;
import scout.materiel.accounts.AccountService;
import scout.materiel.accounts.Roles;
import scout.materiel.accounts.User;
import scout.materiel.common.ApiError;
import scout.materiel.db.Database;
import scout.materiel.inventory.InventoryService;
import scout.materiel.inventory.NomenclatureImporter;
import scout.materiel.loans.LoanRequest;
import scout.materiel.loans.LoanService;

/**
 * API JSON sous /api, consommée par le front-end Angular.
 *
 * Toute route exige une connexion, sauf celles de PUBLIC. Les routes /api/gestion/* sont
 * réservées à l'admin. Les requêtes qui modifient quelque chose doivent porter l'en-tête
 * X-Requested-With (protection CSRF, en plus du cookie SameSite=Lax).
 */
public final class WebApp {
    private static final Logger LOG = LoggerFactory.getLogger(WebApp.class);
    static final String SESSION_COOKIE = "sm_session";
    static final String CSRF_HEADER = "X-Requested-With";
    private static final Set<String> PUBLIC = Set.of(
            "/api/sante", "/api/auth/connexion", "/api/auth/mot-de-passe-oublie", "/api/auth/jeton", "/api/auth/nouveau-mot-de-passe");

    private final Services services;

    private WebApp(Services services) {
        this.services = services;
    }

    public static Javalin create(Services services) {
        return new WebApp(services).build();
    }

    private Javalin build() {
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Javalin app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            // Pi 1 : un seul cœur, peu de mémoire → peu de threads
            config.jetty.threadPool = new QueuedThreadPool(8, 2, 60_000);
            config.jsonMapper(new JavalinJackson(mapper, false));
            config.http.maxRequestSize = 10_000_000L;
            String frontend = services.config.frontendDir;
            if (!frontend.isEmpty()) {
                config.staticFiles.add(files -> {
                    files.directory = frontend;
                    files.location = Location.EXTERNAL;
                });
                config.spaRoot.addFile("/", frontend + "/index.html", Location.EXTERNAL);
            }
        });

        app.before("/api/*", this::authenticate);
        app.exception(ApiError.class, (e, ctx) -> ctx.status(e.status).json(Map.of("errors", e.messages)));
        app.exception(Database.DbException.class, (e, ctx) -> {
            LOG.error("Erreur de base de données sur {}", ctx.path(), e);
            ctx.status(500).json(Map.of("errors", List.of("Erreur interne : réessayez dans un instant.")));
        });
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("Erreur sur {}", ctx.path(), e);
            ctx.status(500).json(Map.of("errors", List.of("Erreur interne : réessayez dans un instant.")));
        });
        app.error(404, "application/json", ctx -> {
            if (ctx.path().startsWith("/api/") && ctx.result() == null) {
                ctx.json(Map.of("errors", List.of("Page introuvable.")));
            }
        });

        app.get("/api/sante", ctx -> ctx.json(Map.of("ok", true)));
        authRoutes(app);
        inventoryRoutes(app);
        cartRoutes(app);
        requestRoutes(app);
        adminRoutes(app);
        return app;
    }

    // Authentification --------------------------------------------------------------------------

    private void authenticate(Context ctx) {
        if (ctx.method() == HandlerType.OPTIONS) {
            return;
        }
        boolean safe = ctx.method() == HandlerType.GET || ctx.method() == HandlerType.HEAD;
        if (!safe && ctx.header(CSRF_HEADER) == null) {
            throw new ApiError(403, "Requête refusée (en-tête de sécurité manquant).");
        }
        services.accounts.sessionUser(ctx.cookie(SESSION_COOKIE)).ifPresent(user -> ctx.attribute("user", user));
        if (ctx.attribute("user") == null && !PUBLIC.contains(ctx.path())) {
            throw new ApiError(401, "Connectez-vous pour continuer.");
        }
        if (ctx.path().startsWith("/api/gestion/")) {
            Roles.requireAdmin(user(ctx));
        }
    }

    static User user(Context ctx) {
        return ctx.attribute("user");
    }

    private void setSessionCookie(Context ctx, String token, int maxAge) {
        StringBuilder cookie = new StringBuilder(SESSION_COOKIE).append('=').append(token)
                .append("; Path=/; Max-Age=").append(maxAge).append("; HttpOnly; SameSite=Lax");
        if (services.config.https) {
            cookie.append("; Secure");
        }
        ctx.res().addHeader("Set-Cookie", cookie.toString());
    }

    private void login(Context ctx, User user) {
        setSessionCookie(ctx, services.accounts.createSession(user.id), services.config.sessionDays * 86_400);
        ctx.json(me(user));
    }

    /** Profil + compteurs affichés dans l'en-tête (panier, demandes à traiter). */
    private Map<String, Object> me(User user) {
        Map<String, Object> me = new HashMap<>();
        me.put("id", user.id);
        me.put("username", user.username);
        me.put("email", user.email);
        me.put("firstName", user.firstName);
        me.put("lastName", user.lastName);
        me.put("displayName", user.displayName());
        me.put("admin", Roles.isAdmin(user));
        me.put("cartCount", services.loans.cartCount(user.id));
        me.put("itemCount", services.inventory.activeItemCount());
        if (Roles.isAdmin(user)) {
            me.put("pendingRequests", services.loans.pendingRequestCount());
            me.put("guestCount", services.accounts.activeGuestCount());
        }
        return me;
    }

    public static class LoginForm {
        public String login;
        public String password;
    }

    public static class PasswordForm {
        public String current;
        public String password;
        public String token;
        public String email;
    }

    private void authRoutes(Javalin app) {
        app.post("/api/auth/connexion", ctx -> {
            LoginForm form = ctx.bodyAsClass(LoginForm.class);
            login(ctx, services.accounts.authenticate(form.login, form.password));
        });
        app.post("/api/auth/deconnexion", ctx -> {
            services.accounts.deleteSession(ctx.cookie(SESSION_COOKIE));
            setSessionCookie(ctx, "", 0);
            ctx.status(204);
        });
        app.get("/api/auth/moi", ctx -> ctx.json(me(user(ctx))));
        app.post("/api/auth/mot-de-passe", ctx -> {
            PasswordForm form = ctx.bodyAsClass(PasswordForm.class);
            User user = user(ctx);
            services.accounts.changePassword(user, form.current, form.password);
            // Les autres sessions sont fermées : on en rouvre une pour cet appareil
            login(ctx, user);
        });
        app.post("/api/auth/mot-de-passe-oublie", ctx -> {
            services.accounts.requestPasswordReset(ctx.bodyAsClass(PasswordForm.class).email);
            ctx.status(204);
        });
        app.get("/api/auth/jeton", ctx -> {
            User user = services.accounts.userFromToken(ctx.queryParam("jeton")).orElseThrow(() -> ApiError.invalid(
                    "Ce lien n'est plus valable (déjà utilisé ou expiré). Demandez-en un nouveau."));
            ctx.json(Map.of("username", user.username, "firstName", user.firstName, "invitation", !user.hasPassword()));
        });
        app.post("/api/auth/nouveau-mot-de-passe", ctx -> {
            PasswordForm form = ctx.bodyAsClass(PasswordForm.class);
            login(ctx, services.accounts.resetPassword(form.token, form.password));
        });
    }

    // Matériel --------------------------------------------------------------------------------

    private void inventoryRoutes(Javalin app) {
        app.get("/api/materiel", ctx -> {
            User user = user(ctx);
            ctx.json(services.inventory.listPage(user, longParam(ctx, "cat"), ctx.queryParam("q"), cart(user)));
        });
        app.get("/api/materiel/{id}", ctx -> {
            User user = user(ctx);
            ctx.json(services.inventory.itemPage(user, pathId(ctx), cart(user)));
        });
    }

    private Map<Long, Integer> cart(User user) {
        return services.db.read(tx -> services.loans.cartQuantities(tx, user.id));
    }

    // Panier ------------------------------------------------------------------------------------

    public static class CartForm {
        public long item;
        public Integer quantity;
    }

    private void cartRoutes(Javalin app) {
        app.get("/api/panier", ctx -> ctx.json(services.loans.cart(user(ctx))));
        app.post("/api/panier", ctx -> {
            CartForm form = ctx.bodyAsClass(CartForm.class);
            ctx.json(services.loans.addToCart(user(ctx), form.item, form.quantity == null ? 1 : form.quantity));
        });
        app.put("/api/panier/{id}", ctx -> {
            CartForm form = ctx.bodyAsClass(CartForm.class);
            ctx.json(services.loans.updateCart(user(ctx), pathId(ctx), form.quantity == null ? 0 : form.quantity));
        });
        app.delete("/api/panier/{id}", ctx -> ctx.json(services.loans.updateCart(user(ctx), pathId(ctx), 0)));
        app.post("/api/panier/envoyer", ctx -> {
            long id = services.loans.submitCart(user(ctx));
            // Les mails partent après l'enregistrement : une panne SMTP ne bloque rien
            LoanRequest request = services.loans.findRequest(id).orElseThrow();
            boolean mailOk = services.notifications.newRequest(request);
            ctx.json(Map.of("id", id, "mailOk", mailOk));
        });
    }

    // Demandes de l'invité --------------------------------------------------------------------------

    private void requestRoutes(Javalin app) {
        app.get("/api/demandes", ctx -> ctx.json(services.loans.myRequests(user(ctx))));
        app.get("/api/demandes/{id}", ctx -> ctx.json(services.loans.requestFor(user(ctx), pathId(ctx), false)));
        app.post("/api/demandes/{id}/annuler", ctx -> {
            User user = user(ctx);
            long id = pathId(ctx);
            List<LoanRequest.Line> cancelled = services.loans.cancelRequest(id, user);
            boolean mailOk = services.notifications.cancelled(services.loans.findRequest(id).orElseThrow(), cancelled);
            ctx.json(Map.of("mailOk", mailOk));
        });
    }

    // Gestion (admin) ---------------------------------------------------------------------------------

    public static class DecisionsForm {
        public String bulk;
        public List<LoanService.Decision> decisions;
    }

    private void adminRoutes(Javalin app) {
        app.get("/api/gestion/demandes", ctx -> ctx.json(services.loans.manageList(ctx.queryParam("onglet"))));
        app.get("/api/gestion/demandes/{id}", ctx -> ctx.json(services.loans.requestFor(user(ctx), pathId(ctx), true)));
        app.post("/api/gestion/demandes/{id}/decisions", ctx -> {
            DecisionsForm form = ctx.bodyAsClass(DecisionsForm.class);
            int changed = services.loans.applyDecisions(pathId(ctx), form.bulk, form.decisions);
            ctx.json(Map.of("changed", changed));
        });

        app.get("/api/gestion/materiel", ctx -> ctx.json(services.inventory.adminItems(
                longParam(ctx, "cat"), ctx.queryParam("q"), ctx.queryParam("etat"))));
        app.get("/api/gestion/materiel/{id}", ctx -> ctx.json(services.inventory.adminItem(pathId(ctx))));
        app.post("/api/gestion/materiel", ctx -> ctx.status(201).json(
                services.inventory.saveItem(null, ctx.bodyAsClass(InventoryService.ItemForm.class))));
        app.put("/api/gestion/materiel/{id}", ctx -> ctx.json(
                services.inventory.saveItem(pathId(ctx), ctx.bodyAsClass(InventoryService.ItemForm.class))));
        app.delete("/api/gestion/materiel/{id}", ctx -> {
            services.inventory.deleteItem(pathId(ctx));
            ctx.status(204);
        });

        app.get("/api/gestion/categories", ctx -> ctx.json(services.inventory.categories()));
        app.post("/api/gestion/categories", ctx -> {
            services.inventory.saveCategory(null, ctx.bodyAsClass(InventoryService.CategoryForm.class));
            ctx.status(201).json(services.inventory.categories());
        });
        app.put("/api/gestion/categories/{id}", ctx -> {
            services.inventory.saveCategory(pathId(ctx), ctx.bodyAsClass(InventoryService.CategoryForm.class));
            ctx.json(services.inventory.categories());
        });
        app.delete("/api/gestion/categories/{id}", ctx -> {
            services.inventory.deleteCategory(pathId(ctx));
            ctx.json(services.inventory.categories());
        });

        app.get("/api/gestion/comptes", ctx -> ctx.json(services.accounts.listUsers().stream().map(this::account).toArray()));
        app.get("/api/gestion/comptes/{id}", ctx -> ctx.json(account(services.accounts.findUser(pathId(ctx))
                .orElseThrow(() -> ApiError.notFound("Compte introuvable.")))));
        app.post("/api/gestion/comptes", ctx -> {
            AccountForm form = ctx.bodyAsClass(AccountForm.class);
            User user = services.accounts.createUser(form);
            boolean invited = form.sendInvitation && services.accounts.sendInvitation(user);
            ctx.status(201).json(Map.of("account", account(user), "invited", invited));
        });
        app.put("/api/gestion/comptes/{id}", ctx -> ctx.json(account(
                services.accounts.updateUser(pathId(ctx), ctx.bodyAsClass(AccountForm.class), user(ctx)))));
        app.post("/api/gestion/comptes/{id}/invitation", ctx -> {
            User user = services.accounts.findUser(pathId(ctx)).orElseThrow(() -> ApiError.notFound("Compte introuvable."));
            if (!user.active) {
                throw ApiError.invalid("Ce compte est désactivé.");
            }
            ctx.json(Map.of("invited", services.accounts.sendInvitation(user)));
        });

        app.post("/api/gestion/import", ctx -> {
            UploadedFile file = ctx.uploadedFile("fichier");
            if (file == null) {
                throw ApiError.invalid("Choisissez un fichier .xlsx.");
            }
            boolean dryRun = "true".equals(ctx.formParam("simulation"));
            try (InputStream in = file.content()) {
                ctx.json(new NomenclatureImporter(services.db).run(in, ctx.formParam("onglet"), dryRun));
            } catch (IOException e) {
                throw ApiError.invalid("Lecture du fichier impossible.");
            }
        });
    }

    public static class AccountForm extends AccountService.UserForm {
        public boolean sendInvitation;
    }

    /** Compte vu par l'admin : rôle calculé par Roles, mot de passe jamais exposé. */
    private Map<String, Object> account(User user) {
        Map<String, Object> account = new HashMap<>();
        account.put("id", user.id);
        account.put("username", user.username);
        account.put("email", user.email);
        account.put("firstName", user.firstName);
        account.put("lastName", user.lastName);
        account.put("displayName", user.displayName());
        account.put("admin", user.adminFlag);
        account.put("active", user.active);
        account.put("hasPassword", user.hasPassword());
        account.put("dateJoined", user.dateJoined);
        account.put("lastLogin", user.lastLogin);
        return account;
    }

    // Outils --------------------------------------------------------------------------------------

    private static long pathId(Context ctx) {
        try {
            return Long.parseLong(ctx.pathParam("id"));
        } catch (NumberFormatException e) {
            throw ApiError.notFound("Page introuvable.");
        }
    }

    private static Long longParam(Context ctx, String name) {
        String value = ctx.queryParam(name);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiError.notFound("Catégorie introuvable.");
        }
    }
}
