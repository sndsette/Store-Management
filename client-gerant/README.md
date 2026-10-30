# client-gerant (JavaFX)

Run:  mvn javafx:run   (the server must be running)
Set the env variable API_URL if needed (default http://localhost:7070)

Expected endpoints:
  POST /login            {login, motDePasse} -> {id, nom, role}   (401 if wrong)
  GET  /produits         -> [ {id, codeBarre, nom, prix, quantiteStock, seuilAlerte, dateExpiration "yyyy-MM-dd", categorie} ]
  POST /produits         body = produit
  PUT  /produits/{id}    body = produit
  DELETE /produits/{id}
