package gerant.controller;

import gerant.model.Produit;
import javafx.fxml.FXML;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class ProduitFormController {
    @FXML private TextField codeField, nomField, prixField, quantiteField, seuilField, categorieField;
    @FXML private DatePicker datePicker;
    @FXML private Label errorLabel, titleLabel;

    private Produit result;

    /** Pass null to add a new product, or an existing one to modify it. */
    public void setProduit(Produit p) {
        if (p == null) return;
        titleLabel.setText("Modifier le produit");
        codeField.setText(p.getCodeBarre());
        nomField.setText(p.getNom());
        prixField.setText(String.valueOf(p.getPrix()));
        quantiteField.setText(String.valueOf(p.getQuantiteStock()));
        seuilField.setText(String.valueOf(p.getSeuilAlerte()));
        categorieField.setText(p.getCategorie());
        datePicker.setValue(p.getDateExpiration());
    }

    /** null if the user cancelled. */
    public Produit getResult() { return result; }

    @FXML
    private void onSave() {
        String code = codeField.getText().trim();
        String nom = nomField.getText().trim();
        if (code.isEmpty() || nom.isEmpty()) { errorLabel.setText("Code-barres et nom obligatoires"); return; }

        double prix;
        int qte, seuil;
        try {
            prix = Double.parseDouble(prixField.getText().trim().replace(',', '.'));
            if (prix <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { errorLabel.setText("Prix invalide (nombre > 0)"); return; }
        try {
            qte = Integer.parseInt(quantiteField.getText().trim());
            if (qte < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { errorLabel.setText("Quantité invalide (entier >= 0)"); return; }
        try {
            seuil = Integer.parseInt(seuilField.getText().trim());
            if (seuil < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) { errorLabel.setText("Seuil invalide (entier >= 0)"); return; }

        result = new Produit(0, code, nom, prix, qte, seuil, datePicker.getValue(),
                categorieField.getText().trim());
        close();
    }

    @FXML private void onCancel() { result = null; close(); }

    private void close() { ((Stage) nomField.getScene().getWindow()).close(); }
}
