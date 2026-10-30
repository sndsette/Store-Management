package server;

import org.mindrot.jbcrypt.BCrypt;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class AuthDao {
    private final Db db;

    public AuthDao(Db db) { this.db = db; }

    /** Returns the user, or null if login/password are wrong. */
    public Utilisateur login(String login, String motDePasse) throws SQLException {
        if (login == null || motDePasse == null) return null;
        String sql = "SELECT id, nom, role, mot_de_passe FROM utilisateur WHERE login = ?";
        try (Connection c = db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, login);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!BCrypt.checkpw(motDePasse, rs.getString("mot_de_passe"))) return null;
                return new Utilisateur(rs.getInt("id"), rs.getString("nom"), rs.getString("role"));
            }
        }
    }
}
