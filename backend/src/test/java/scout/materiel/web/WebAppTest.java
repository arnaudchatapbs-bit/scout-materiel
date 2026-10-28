package scout.materiel.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.Javalin;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import scout.materiel.Fixtures;

/** Permissions et sécurité de l'API, de bout en bout (vrai serveur HTTP). */
class WebAppTest {
    Fixtures f;
    Javalin app;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        f.admin();
        f.guest();
        app = WebApp.create(f.services).start("127.0.0.1", 0);
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterEach
    void tearDown() {
        app.stop();
    }

    HttpResponse<String> send(String method, String path, String cookie, String body, boolean csrf) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (csrf) {
            request.header("X-Requested-With", "ScoutMateriel");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    String login(String username) throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/connexion", null,
                "{\"login\":\"" + username + "\",\"password\":\"" + Fixtures.PASSWORD + "\"}", true);
        assertEquals(200, response.statusCode(), response.body());
        String setCookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        return setCookie.split(";")[0];
    }

    @Test
    void everythingRequiresLoginExceptPublicRoutes() throws Exception {
        assertEquals(401, send("GET", "/api/materiel", null, null, false).statusCode());
        assertEquals(401, send("GET", "/api/auth/moi", null, null, false).statusCode());
        assertEquals(200, send("GET", "/api/sante", null, null, false).statusCode());
    }

    @Test
    void guestsCannotUseAdminRoutes() throws Exception {
        String guest = login("invite");
        assertEquals(200, send("GET", "/api/materiel", guest, null, false).statusCode());
        assertEquals(403, send("GET", "/api/gestion/demandes", guest, null, false).statusCode());
        assertEquals(403, send("GET", "/api/gestion/comptes", guest, null, false).statusCode());
        assertEquals(403, send("POST", "/api/gestion/materiel", guest, "{\"name\":\"x\"}", true).statusCode());

        String admin = login("chef");
        assertEquals(200, send("GET", "/api/gestion/demandes", admin, null, false).statusCode());
    }

    @Test
    void writesRequireCsrfHeader() throws Exception {
        String guest = login("invite");
        HttpResponse<String> response = send("POST", "/api/panier/envoyer", guest, "{}", false);
        assertEquals(403, response.statusCode());
        assertEquals(400, send("POST", "/api/panier/envoyer", guest, "{}", true).statusCode(), "panier vide");
    }

    @Test
    void meReportsRoleAndCounters() throws Exception {
        HttpResponse<String> response = send("GET", "/api/auth/moi", login("chef"), null, false);
        assertTrue(response.body().contains("\"admin\":true"), response.body());
        assertTrue(response.body().contains("\"pendingRequests\":0"));
        response = send("GET", "/api/auth/moi", login("invite"), null, false);
        assertTrue(response.body().contains("\"admin\":false"));
        assertTrue(!response.body().contains("pendingRequests"));
    }

    @Test
    void logoutEndsTheSession() throws Exception {
        String guest = login("invite");
        assertEquals(204, send("POST", "/api/auth/deconnexion", guest, null, true).statusCode());
        assertEquals(401, send("GET", "/api/materiel", guest, null, false).statusCode());
    }

    @Test
    void errorsAreJsonInFrench() throws Exception {
        HttpResponse<String> response = send("POST", "/api/auth/connexion", null, "{\"login\":\"chef\",\"password\":\"faux\"}", true);
        assertEquals(400, response.statusCode());
        assertEquals("{\"errors\":[\"Identifiant ou mot de passe incorrect.\"]}", response.body());
    }

    @Test
    void passwordHashIsNeverExposed() throws Exception {
        HttpResponse<String> response = send("GET", "/api/gestion/comptes", login("chef"), null, false);
        assertTrue(!response.body().contains("pbkdf2"), response.body());
    }
}
