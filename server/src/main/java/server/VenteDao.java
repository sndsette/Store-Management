package server;

import server.Dto.LigneReq;
import server.Dto.VenteReq;
import server.Dto.VenteResp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class VenteDao {
    private final Db db;

    public VenteDao(Db db) { this.db = db; }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Saves the sale and decreases the stock, all in ONE transaction. */
    public VenteResp create(VenteReq r) throws SQLException {
        if (r == null || r.lignes == null || r.lignes.isEmpty()) throw new ApiException(400, "Panier vide.");

        Map<Integer, Integer> qty = new LinkedHashMap<>();   // merge duplicate products
        for (LigneReq l : r.lignes) {
            if (l.quantite <= 0) throw new ApiException(400, "Quantité invalide.");
            qty.merge(l.idProduit, l.quantite, Integer::sum);
        }

        try (Connection c = db.get()) {
            c.setAutoCommit(false);
            try {
                double total = 0;
                Map<Integer, Double> prix = new HashMap<>();
                for (Map.Entry<Integer, Integer> e : qty.entrySet()) {
                    String sql = "SELECT nom, prix, quantite_stock FROM produit WHERE id = ? FOR UPDATE";
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setInt(1, e.getKey());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new ApiException(404, "Produit introuvable (id " + e.getKey() + ").");
                            if (rs.getInt("quantite_stock") < e.getValue())
                                throw new ApiException(409, "Stock insuffisant pour « " + rs.getString("nom") + " ».");
                            double p = rs.getDouble("prix");
                            prix.put(e.getKey(), p);
                            total += p * e.getValue();
                        }
                    }
                }
                total = round2(total);
                if (r.montantRecu < total) throw new ApiException(400, "Montant reçu insuffisant.");
                double monnaie = round2(r.montantRecu - total);

                int venteId;
                String insVente = "INSERT INTO vente (id_caissier, total, montant_recu, monnaie_rendue) "
                        + "VALUES (?,?,?,?) RETURNING id";
                try (PreparedStatement ps = c.prepareStatement(insVente)) {
                    ps.setInt(1, r.idCaissier);
                    ps.setDouble(2, total);
                    ps.setDouble(3, r.montantRecu);
                    ps.setDouble(4, monnaie);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        venteId = rs.getInt(1);
                    }
                }

                for (Map.Entry<Integer, Integer> e : qty.entrySet()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO ligne_vente (id_vente, id_produit, quantite, prix_unitaire) VALUES (?,?,?,?)")) {
                        ps.setInt(1, venteId);
                        ps.setInt(2, e.getKey());
                        ps.setInt(3, e.getValue());
                        ps.setDouble(4, prix.get(e.getKey()));
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE produit SET quantite_stock = quantite_stock - ? WHERE id = ?")) {
                        ps.setInt(1, e.getValue());
                        ps.setInt(2, e.getKey());
                        ps.executeUpdate();
                    }
                }
                c.commit();

                VenteResp resp = new VenteResp();
                resp.id = venteId;
                resp.total = total;
                resp.monnaieRendue = monnaie;
                return resp;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }
}
