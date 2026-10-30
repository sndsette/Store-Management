# SuperGest - gestion de supermarché (client / serveur)

```
supermarket/
├── server/          API REST (Javalin) + base PostgreSQL sur Neon
├── client-gerant/   application JavaFX du gérant
├── client-caissier/ (à ajouter par snds)
└── sql/schema.sql   tables + données de test
```

## Lancer le serveur
1. Créer un projet sur neon.tech et exécuter `sql/schema.sql` dans le SQL Editor.
2. Copier `server/.env.example` en `server/.env` et y coller la chaîne de connexion Neon.
3. `cd server && mvn compile exec:java`  ->  http://localhost:7070

## Lancer le client gérant
`cd client-gerant && mvn javafx:run`

Comptes de test : gerant / 1234  et  caissier / 1234

## Règles Git
- Ne jamais pousser le fichier `.env` (mot de passe Neon).
- Une branche par personne (`server`, `caissier`, `gerant`), fusion dans `main` par pull request.
