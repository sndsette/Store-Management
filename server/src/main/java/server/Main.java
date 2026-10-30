package server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import io.javalin.Javalin;
import io.javalin.json.JsonMapper;
import server.Dto.LoginReq;
import server.Dto.VenteReq;

import java.lang.reflect.Type;
import java.sql.SQLException;
import java.time.LocalDate;

public class Main {

    public static void main(String[] args) {
        Config cfg;
        Db db;
        try {
            cfg = new Config();
            db = new Db(cfg);
            db.ping();
        } catch (Exception e) {
            System.err.println("\n[ERREUR] Impossible de démarrer : " + e.getMessage());
            System.err.println("Vérifiez server/.env (DATABASE_URL) et que schema.sql a été exécuté sur Neon.");
            System.exit(1);
            return;
        }
        final Db database = db;

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class,
                        (JsonSerializer<LocalDate>) (d, t, c) -> new JsonPrimitive(d.toString()))
                .registerTypeAdapter(LocalDate.class,
                        (JsonDeserializer<LocalDate>) (j, t, c) ->
                                (j.isJsonNull() || j.getAsString().isBlank()) ? null : LocalDate.parse(j.getAsString()))
                .create();

        JsonMapper mapper = new JsonMapper() {
            @Override public String toJsonString(Object obj, Type type) { return gson.toJson(obj, type); }
            @Override public <T> T fromJsonString(String json, Type targetType) { return gson.fromJson(json, targetType); }
        };

        AuthDao auth = new AuthDao(database);
        ProduitDao produits = new ProduitDao(database);
        VenteDao ventes = new VenteDao(database);

        Javalin app = Javalin.create(config -> config.jsonMapper(mapper));

        app.get("/", ctx -> ctx.result("SuperGest API OK"));
        app.get("/health", ctx -> { database.ping(); ctx.result("OK"); });

        app.post("/login", ctx -> {
            LoginReq r = ctx.bodyAsClass(LoginReq.class);
            Utilisateur u = auth.login(r.login, r.motDePasse);
            if (u == null) { ctx.status(401).result("Login ou mot de passe incorrect"); return; }
            ctx.json(u);
        });

        app.get("/produits", ctx -> ctx.json(produits.findAll()));

        app.get("/produits/code/{code}", ctx -> {
            Produit p = produits.findByCode(ctx.pathParam("code"));
            if (p == null) throw new ApiException(404, "Produit introuvable");
            ctx.json(p);
        });

        app.post("/produits", ctx -> {
            Produit p = valid(ctx.bodyAsClass(Produit.class));
            ctx.status(201).json(produits.insert(p));
        });

        app.put("/produits/{id}", ctx -> {
            Produit p = valid(ctx.bodyAsClass(Produit.class));
            p.id = Integer.parseInt(ctx.pathParam("id"));
            if (!produits.update(p)) throw new ApiException(404, "Produit introuvable");
            ctx.json(p);
        });

        app.delete("/produits/{id}", ctx -> {
            if (!produits.delete(Integer.parseInt(ctx.pathParam("id"))))
                throw new ApiException(404, "Produit introuvable");
            ctx.status(204);
        });

        app.post("/ventes", ctx -> ctx.status(201).json(ventes.create(ctx.bodyAsClass(VenteReq.class))));

        app.exception(ApiException.class, (e, ctx) -> ctx.status(e.status).result(e.getMessage()));
        app.exception(JsonParseException.class, (e, ctx) -> ctx.status(400).result("JSON invalide"));
        app.exception(SQLException.class, (e, ctx) -> {
            String st = e.getSQLState();
            if ("23505".equals(st)) ctx.status(409).result("Ce code-barres existe déjà.");
            else if ("23503".equals(st)) ctx.status(409).result("Opération impossible : élément lié (ex : produit déjà vendu).");
            else {
                e.printStackTrace();
                ctx.status(500).result("Erreur base de données : " + e.getMessage());
            }
        });
        app.exception(Exception.class, (e, ctx) -> {
            e.printStackTrace();
            ctx.status(500).result("Erreur serveur : " + e.getMessage());
        });

        app.start(cfg.port());
        System.out.println("\n=== SuperGest server démarré : http://localhost:" + cfg.port() + " ===\n");
    }

    private static Produit valid(Produit p) {
        if (p == null) throw new ApiException(400, "Corps de requête vide.");
        if (p.codeBarre == null || p.codeBarre.isBlank()) throw new ApiException(400, "Code-barres obligatoire.");
        if (p.nom == null || p.nom.isBlank()) throw new ApiException(400, "Nom obligatoire.");
        if (p.prix <= 0) throw new ApiException(400, "Le prix doit être > 0.");
        if (p.quantiteStock < 0) throw new ApiException(400, "La quantité ne peut pas être négative.");
        if (p.seuilAlerte < 0) throw new ApiException(400, "Le seuil ne peut pas être négatif.");
        return p;
    }
}
