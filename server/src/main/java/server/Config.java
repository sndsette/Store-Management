package server;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads settings from environment variables, or from a ".env" file in the working directory. */
public class Config {
    private final Map<String, String> file = new HashMap<>();
    private String jdbcUrl, dbUser, dbPassword;
    private final int port;

    public Config() {
        Path p = Paths.get(".env");
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i < 0) continue;
                    file.put(line.substring(0, i).trim(), unquote(line.substring(i + 1).trim()));
                }
            } catch (IOException e) {
                throw new RuntimeException("Lecture de .env impossible : " + e.getMessage());
            }
        }
        String url = get("DATABASE_URL");
        if (url == null)
            throw new IllegalStateException("DATABASE_URL manquant. Créez le fichier server/.env (voir .env.example).");
        parse(url);
        String portStr = get("PORT");
        port = portStr == null ? 7070 : Integer.parseInt(portStr);
    }

    private String get(String key) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : file.get(key);
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))))
            return v.substring(1, v.length() - 1);
        return v;
    }

    /** Accepts the Neon string: postgresql://user:password@host/db?sslmode=require */
    private void parse(String raw) {
        String url = raw.trim();
        if (url.startsWith("psql")) url = url.substring(4).trim();   // "psql 'postgresql://...'"
        url = unquote(url);
        try {
            URI u = new URI(url.replaceFirst("^postgres(ql)?://", "http://"));
            String ui = u.getUserInfo();
            if (ui == null || !ui.contains(":") || u.getHost() == null)
                throw new IllegalArgumentException("format attendu : postgresql://user:motdepasse@hôte/base");
            dbUser = ui.substring(0, ui.indexOf(':'));
            dbPassword = ui.substring(ui.indexOf(':') + 1);

            List<String> params = new ArrayList<>();
            if (u.getRawQuery() != null)
                for (String s : u.getRawQuery().split("&"))
                    if (!s.isBlank() && !s.startsWith("channel_binding")) params.add(s);
            if (params.stream().noneMatch(s -> s.startsWith("sslmode"))) params.add("sslmode=require");

            jdbcUrl = "jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "")
                    + u.getRawPath() + "?" + String.join("&", params);
        } catch (Exception e) {
            throw new IllegalArgumentException("DATABASE_URL invalide : " + e.getMessage());
        }
    }

    public String jdbcUrl() { return jdbcUrl; }
    public String dbUser() { return dbUser; }
    public String dbPassword() { return dbPassword; }
    public int port() { return port; }
}
