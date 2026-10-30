package gerant.service;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import gerant.model.Produit;
import gerant.model.Utilisateur;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Real implementation: talks to Rahima's server (REST + JSON). */
public class ApiClient implements ProduitService, AuthService {
    private static final String BASE =
            System.getenv().getOrDefault("API_URL", "http://localhost:7070");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();

    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDate.class,
                    (JsonSerializer<LocalDate>) (d, t, c) -> new JsonPrimitive(d.toString()))
            .registerTypeAdapter(LocalDate.class,
                    (JsonDeserializer<LocalDate>) (j, t, c) -> LocalDate.parse(j.getAsString()))
            .create();

    private HttpResponse<String> send(HttpRequest.Builder b) {
        try {
            return http.send(b.timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").build(),
                    BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException("Serveur injoignable (" + BASE + ")");
        }
    }

    private String ok(HttpResponse<String> r) {
        if (r.statusCode() >= 400)
            throw new RuntimeException("Erreur serveur " + r.statusCode() + " : " + r.body());
        return r.body();
    }

    private HttpRequest.Builder req(String path) { return HttpRequest.newBuilder(URI.create(BASE + path)); }

    @Override public Utilisateur login(String login, String mdp) {
        String body = gson.toJson(Map.of("login", login, "motDePasse", mdp));
        HttpResponse<String> r = send(req("/login").POST(BodyPublishers.ofString(body)));
        if (r.statusCode() == 401) throw new RuntimeException("Login ou mot de passe incorrect");
        return gson.fromJson(ok(r), Utilisateur.class);
    }

    @Override public List<Produit> getProduits() {
        String json = ok(send(req("/produits").GET()));
        return gson.fromJson(json, new TypeToken<List<Produit>>() {}.getType());
    }

    @Override public void addProduit(Produit p) {
        ok(send(req("/produits").POST(BodyPublishers.ofString(gson.toJson(p)))));
    }

    @Override public void updateProduit(Produit p) {
        ok(send(req("/produits/" + p.getId()).PUT(BodyPublishers.ofString(gson.toJson(p)))));
    }

    @Override public void deleteProduit(int id) {
        ok(send(req("/produits/" + id).DELETE()));
    }
}
