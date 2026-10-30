package gerant.controller;

import gerant.App;
import gerant.model.Utilisateur;
import gerant.service.Services;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;

public class LoginController {
    @FXML private TextField loginField;
    @FXML private PasswordField passField;
    @FXML private Label errorLabel, hintLabel;
    @FXML private Button loginBtn;

    @FXML
    private void onLogin() {
        String l = loginField.getText().trim();
        String p = passField.getText();
        if (l.isEmpty() || p.isEmpty()) {
            errorLabel.setText("Veuillez remplir tous les champs.");
            return;
        }
        loginBtn.setDisable(true);
        loginBtn.setText("Connexion...");
        errorLabel.setText("");

        Task<Utilisateur> task = new Task<>() {
            @Override protected Utilisateur call() { return Services.auth.login(l, p); }
        };
        task.setOnSucceeded(e -> {
            Utilisateur u = task.getValue();
            if (!"gerant".equals(u.getRole())) {
                fail("Accès réservé au gérant.");
                return;
            }
            openDashboard(u);
        });
        task.setOnFailed(e -> fail(task.getException().getMessage()));
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private void fail(String msg) {
        errorLabel.setText(msg);
        loginBtn.setDisable(false);
        loginBtn.setText("Se connecter");
    }

    private void openDashboard(Utilisateur u) {
        try {
            FXMLLoader fl = new FXMLLoader(getClass().getResource("/fxml/dashboard.fxml"));
            Parent root = fl.load();
            DashboardController c = fl.getController();
            c.setUser(u);
            Stage s = (Stage) loginBtn.getScene().getWindow();
            s.setResizable(true);
            s.setScene(App.scene(root, 1240, 780));
            s.setMinWidth(1080);
            s.setMinHeight(700);
            s.centerOnScreen();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }
}
