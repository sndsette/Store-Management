package gerant.service;

public class Services {
    private static final ApiClient API = new ApiClient();

    public static final AuthService auth = API;
    public static final ProduitService produits = API;
}
