package server;

import java.time.LocalDate;

/** Field names must match the JSON used by the clients. */
public class Produit {
    public int id;
    public String codeBarre;
    public String nom;
    public double prix;
    public int quantiteStock;
    public int seuilAlerte;
    public LocalDate dateExpiration;
    public String categorie;
}
