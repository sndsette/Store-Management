package server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public class ProduitDao {
    private static final String COLS =
            "id, code_barre, nom, prix, quantite_stock, seuil_alerte, date_expiration, categorie";
    private final Db db;

    public ProduitDao(Db db) { this.db = db; }

    private Produit map(ResultSet rs) throws SQLException {
        Produit p = new Produit();
        p.id = rs.getInt("id");
        p.codeBarre = rs.getString("code_barre");
        p.nom = rs.getString("nom");
        p.prix = rs.getDouble("prix");
        p.quantiteStock = rs.getInt("quantite_stock");
        p.seuilAlerte = rs.getInt("seuil_alerte");
        java.sql.Date d = rs.getDate("date_expiration");
        p.dateExpiration = d == null ? null : d.toLocalDate();
        p.categorie = rs.getString("categorie");
        return p;
    }

    private void bind(PreparedStatement ps, Produit p) throws SQLException {
        ps.setString(1, p.codeBarre.trim());
        ps.setString(2, p.nom.trim());
        ps.setDouble(3, p.prix);
        ps.setInt(4, p.quantiteStock);
        ps.setInt(5, p.seuilAlerte);
        if (p.dateExpiration == null) ps.setNull(6, Types.DATE);
        else ps.setDate(6, java.sql.Date.valueOf(p.dateExpiration));
        ps.setString(7, p.categorie);
    }

    public List<Produit> findAll() throws SQLException {
        List<Produit> list = new ArrayList<>();
        try (Connection c = db.get();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM produit ORDER BY nom");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(map(rs));
        }
        return list;
    }

    public Produit findByCode(String code) throws SQLException {
        try (Connection c = db.get();
             PreparedStatement ps = c.prepareStatement("SELECT " + COLS + " FROM produit WHERE code_barre = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public Produit insert(Produit p) throws SQLException {
        String sql = "INSERT INTO produit (code_barre, nom, prix, quantite_stock, seuil_alerte, date_expiration, categorie) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id";
        try (Connection c = db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                p.id = rs.getInt(1);
            }
        }
        return p;
    }

    public boolean update(Produit p) throws SQLException {
        String sql = "UPDATE produit SET code_barre=?, nom=?, prix=?, quantite_stock=?, seuil_alerte=?, "
                + "date_expiration=?, categorie=? WHERE id=?";
        try (Connection c = db.get(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, p);
            ps.setInt(8, p.id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean delete(int id) throws SQLException {
        try (Connection c = db.get(); PreparedStatement ps = c.prepareStatement("DELETE FROM produit WHERE id = ?")) {
            ps.setInt(1, id);
            return ps.executeUpdate() > 0;
        }
    }
}
