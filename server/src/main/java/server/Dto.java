package server;

import java.util.List;

public class Dto {
    public static class LoginReq {
        public String login;
        public String motDePasse;
    }

    public static class LigneReq {
        public int idProduit;
        public int quantite;
    }

    public static class VenteReq {
        public int idCaissier;
        public double montantRecu;
        public List<LigneReq> lignes;
    }

    public static class VenteResp {
        public int id;
        public double total;
        public double monnaieRendue;
    }
}
