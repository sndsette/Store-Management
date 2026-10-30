package gerant.model;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

// TODO: move this class to the shared "common" module once the repo is ready
public class Produit {
    private int id;
    private String codeBarre;
    private String nom;
    private double prix;
    private int quantiteStock;
    private int seuilAlerte;
    private LocalDate dateExpiration;
    private String categorie;

    public Produit() {}

    public Produit(int id, String codeBarre, String nom, double prix, int quantiteStock,
                   int seuilAlerte, LocalDate dateExpiration, String categorie) {
        this.id = id; this.codeBarre = codeBarre; this.nom = nom; this.prix = prix;
        this.quantiteStock = quantiteStock; this.seuilAlerte = seuilAlerte;
        this.dateExpiration = dateExpiration; this.categorie = categorie;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getCodeBarre() { return codeBarre; }
    public void setCodeBarre(String codeBarre) { this.codeBarre = codeBarre; }
    public String getNom() { return nom; }
    public void setNom(String nom) { this.nom = nom; }
    public double getPrix() { return prix; }
    public void setPrix(double prix) { this.prix = prix; }
    public int getQuantiteStock() { return quantiteStock; }
    public void setQuantiteStock(int q) { this.quantiteStock = q; }
    public int getSeuilAlerte() { return seuilAlerte; }
    public void setSeuilAlerte(int s) { this.seuilAlerte = s; }
    public LocalDate getDateExpiration() { return dateExpiration; }
    public void setDateExpiration(LocalDate d) { this.dateExpiration = d; }
    public String getCategorie() { return categorie; }
    public void setCategorie(String c) { this.categorie = c; }

    // ---- business helpers ----
    public boolean isStockFaible() { return quantiteStock <= seuilAlerte; }

    public String getStatutStock() {
        if (isExpire()) return "Expiré";
        if (quantiteStock <= 0) return "Rupture";
        return isStockFaible() ? "Stock faible" : "En stock";
    }

    public boolean isExpire() {
        return dateExpiration != null && dateExpiration.isBefore(LocalDate.now());
    }

    public boolean expireDans(int jours) {
        return dateExpiration != null && !isExpire()
                && !dateExpiration.isAfter(LocalDate.now().plusDays(jours));
    }

    /** Days left before expiry (negative = already expired), null if no date. */
    public Long getJoursRestants() {
        return dateExpiration == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), dateExpiration);
    }
}