package gerant.service;

import gerant.model.Produit;
import java.util.List;

public interface ProduitService {
    List<Produit> getProduits();
    void addProduit(Produit p);
    void updateProduit(Produit p);
    void deleteProduit(int id);
}
