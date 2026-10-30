package gerant.service;

import gerant.model.Utilisateur;

public interface AuthService {
    /** Returns the user, or throws RuntimeException with a message to display. */
    Utilisateur login(String login, String motDePasse);
}
