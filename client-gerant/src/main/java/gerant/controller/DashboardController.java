package gerant.controller;

import gerant.App;
import gerant.model.Produit;
import gerant.model.Utilisateur;
import gerant.service.Services;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DashboardController {
    // root + top bar
    @FXML private Node rootPane;
    @FXML private Label avatarLabel, userNameLabel, userRoleLabel;
    @FXML private Button logoutBtn;
    @FXML private ToggleButton navOverview, navStock, navExp, navProduits;
    // header
    @FXML private Label pageTitle, pageSub, lastUpdateLabel, toastLabel;
    // pages
    @FXML private Node pageOverview, pageStock, pageExp, pageProduits;
    // overview
    @FXML private HBox kpiRow;
    @FXML private PieChart pieChart;
    @FXML private BarChart<String, Number> barChart;
    @FXML private ListView<String> alertList;
    @FXML private Label alertCountLabel;
    // stock / exp / produits
    @FXML private TextField stockSearch, prodSearch;
    @FXML private Label stockCountLabel, expInfoLabel;
    @FXML private Spinner<Integer> daysSpinner;
    @FXML private ToggleGroup chipGroup;
    @FXML private ToggleButton chipAll, chipLow, chipOut, chipSoon, chipExpired;
    @FXML private TableView<Produit> stockTable, expTable, prodTable;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter LONG_DATE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH);

    private final ObservableList<Produit> all = FXCollections.observableArrayList();
    private final FilteredList<Produit> stockFiltered = new FilteredList<>(all, p -> true);
    private final FilteredList<Produit> prodFiltered = new FilteredList<>(all, p -> true);
    private final FilteredList<Produit> expFiltered = new FilteredList<>(all, p -> false);

    private final Label kProd = new Label("0"), kProdSub = new Label();
    private final Label kVal = new Label("0"), kValSub = new Label();
    private final Label kLow = new Label("0"), kLowSub = new Label();
    private final Label kExp = new Label("0"), kExpSub = new Label();

    private String userName = "";
    private SequentialTransition toastAnim;

    // =============== init ===============
    @FXML
    private void initialize() {
        initNav(navOverview, "▦", "Vue générale");
        initNav(navStock, "▤", "Stock");
        initNav(navExp, "⏰", "Expirations");
        initNav(navProduits, "✎", "Produits");

        daysSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 365, 7));

        kpiRow.getChildren().addAll(
                kpi("📦", "Produits", kProd, kProdSub, "kpi-indigo"),
                kpi("💰", "Valeur du stock", kVal, kValSub, "kpi-green"),
                kpi("📉", "Stock faible", kLow, kLowSub, "kpi-amber"),
                kpi("⏰", "Expirés / bientôt", kExp, kExpSub, "kpi-red"));

        setupProductTable(stockTable, true);
        setupProductTable(prodTable, false);
        bind(stockTable, stockFiltered);
        bind(prodTable, prodFiltered);
        setupExpTable();

        prodSearch.textProperty().bindBidirectional(stockSearch.textProperty());
        stockSearch.textProperty().addListener((o, a, b) -> applyFilter());
        chipGroup.selectedToggleProperty().addListener((o, oldT, newT) -> {
            if (newT == null) oldT.setSelected(true);   // never leave "no chip selected"
            else applyFilter();
        });
        daysSpinner.valueProperty().addListener((o, a, b) -> updateExp());

        prodTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && prodTable.getSelectionModel().getSelectedItem() != null)
                onEdit();
        });

        alertList.setPlaceholder(new Label("Aucune alerte 🎉"));
        alertList.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                getStyleClass().removeAll("alert-red", "alert-orange", "alert-amber");
                if (empty || s == null) { setText(null); return; }
                setText(s);
                setWrapText(true);
                getStyleClass().add(s.startsWith("⛔") ? "alert-red" : s.startsWith("⏰") ? "alert-orange" : "alert-amber");
            }
        });
        pieChart.setLegendSide(Side.BOTTOM);

        showOverview();
        refresh();
    }

    public void setUser(Utilisateur u) {
        String n = u.getNom() == null || u.getNom().isEmpty() ? "?" : u.getNom();
        userName = n;
        avatarLabel.setText(n.substring(0, 1).toUpperCase());
        userNameLabel.setText(n);
        userRoleLabel.setText("Gérant");
        if (navOverview.isSelected()) pageSub.setText(overviewSub());
    }

    // =============== navigation ===============
    @FXML private void showOverview() { show(pageOverview, navOverview, "Vue générale", overviewSub()); }
    @FXML private void showStock()    { show(pageStock, navStock, "Stock", "État des stocks de tous les produits"); }
    @FXML private void showExp()      { show(pageExp, navExp, "Dates d'expiration", "Produits expirés ou bientôt périmés"); }
    @FXML private void showProduits() { show(pageProduits, navProduits, "Gestion des produits", "Ajouter, modifier ou supprimer des produits"); }

    private void show(Node page, ToggleButton btn, String title, String sub) {
        for (Node n : List.of(pageOverview, pageStock, pageExp, pageProduits)) {
            boolean on = n == page;
            n.setVisible(on);
            n.setManaged(on);
        }
        btn.setSelected(true);
        pageTitle.setText(title);
        pageSub.setText(sub);
        FadeTransition ft = new FadeTransition(Duration.millis(240), page);
        ft.setFromValue(0);
        ft.setToValue(1);
        ft.play();
    }

    private String overviewSub() {
        String d = LocalDate.now().format(LONG_DATE);
        d = d.substring(0, 1).toUpperCase() + d.substring(1);
        return "Bonjour " + (userName.isEmpty() ? "" : userName + " ") + "👋  ·  " + d;
    }

    private void initNav(ToggleButton b, String icon, String label) {
        b.setText(label);
    }

    // =============== tables ===============
    private TableColumn<Produit, String> col(String title, Function<Produit, String> fn) {
        TableColumn<Produit, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(fn.apply(cd.getValue())));
        return c;
    }

    private TableColumn<Produit, String> numCol(String title, Function<Produit, String> fn) {
        TableColumn<Produit, String> c = col(title, fn);
        c.setComparator(Comparator.comparingDouble(DashboardController::num));
        return c;
    }

    private TableColumn<Produit, String> dateCol(String title) {
        TableColumn<Produit, String> c = col(title, p -> fmt(p.getDateExpiration()));
        c.setComparator(Comparator.comparing(s -> {
            try { return LocalDate.parse(s, DF); } catch (Exception e) { return LocalDate.MAX; }
        }));
        return c;
    }

    private TableColumn<Produit, String> badgeCol(String title, Function<Produit, String> fn) {
        TableColumn<Produit, String> c = col(title, fn);
        c.setCellFactory(tc -> new TableCell<Produit, String>() {
            @Override protected void updateItem(String s, boolean empty) {
                super.updateItem(s, empty);
                if (empty || s == null) { setGraphic(null); setText(null); return; }
                Label b = new Label(s);
                b.getStyleClass().addAll("badge", badgeClass(s));
                setGraphic(b);
                setText(null);
            }
        });
        return c;
    }

    /** Small progress bar: quantity compared to the alert threshold. */
    private TableColumn<Produit, Produit> levelCol() {
        TableColumn<Produit, Produit> c = new TableColumn<>("Niveau");
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(cd.getValue()));
        c.setSortable(false);
        c.setCellFactory(tc -> new TableCell<Produit, Produit>() {
            @Override protected void updateItem(Produit p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) { setGraphic(null); setText(null); return; }
                double max = Math.max(1, p.getSeuilAlerte() * 3.0);
                ProgressBar pb = new ProgressBar(Math.min(1.0, p.getQuantiteStock() / max));
                pb.setPrefWidth(100);
                pb.setMaxWidth(110);
                int q = p.getQuantiteStock(), s = p.getSeuilAlerte();
                pb.getStyleClass().add(q <= s ? "pb-red" : q <= s * 1.5 ? "pb-amber" : "pb-green");
                setGraphic(pb);
                setText(null);
            }
        });
        return c;
    }

    private static String badgeClass(String s) {
        switch (s) {
            case "En stock": case "OK": return "badge-green";
            case "Stock faible": return "badge-amber";
            case "Bientôt": return "badge-orange";
            case "Rupture": case "Expiré": return "badge-red";
            default: return "badge-gray";
        }
    }

    private void setupProductTable(TableView<Produit> t, boolean withLevel) {
        t.getColumns().add(col("Code-barres", Produit::getCodeBarre));
        t.getColumns().add(col("Produit", Produit::getNom));
        t.getColumns().add(col("Catégorie", p -> p.getCategorie() == null ? "—" : p.getCategorie()));
        t.getColumns().add(numCol("Prix (DA)", p -> String.format(Locale.US, "%.2f", p.getPrix())));
        t.getColumns().add(numCol("Quantité", p -> String.valueOf(p.getQuantiteStock())));
        t.getColumns().add(numCol("Seuil", p -> String.valueOf(p.getSeuilAlerte())));
        if (withLevel) t.getColumns().add(levelCol());
        t.getColumns().add(dateCol("Expiration"));
        t.getColumns().add(badgeCol("Statut", Produit::getStatutStock));
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        t.setPlaceholder(new Label("Aucun produit"));
    }

    private void setupExpTable() {
        TableColumn<Produit, String> date = dateCol("Expiration");
        expTable.getColumns().add(col("Produit", Produit::getNom));
        expTable.getColumns().add(col("Catégorie", p -> p.getCategorie() == null ? "—" : p.getCategorie()));
        expTable.getColumns().add(numCol("Quantité", p -> String.valueOf(p.getQuantiteStock())));
        expTable.getColumns().add(date);
        expTable.getColumns().add(numCol("Jours restants",
                p -> p.getJoursRestants() == null ? "—" : p.getJoursRestants() + " j"));
        expTable.getColumns().add(badgeCol("Statut", this::statutExp));
        expTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        expTable.setPlaceholder(new Label("Aucun produit à surveiller 🎉"));
        bind(expTable, expFiltered);
        expTable.getSortOrder().add(date);   // soonest first
    }

    private String statutExp(Produit p) {
        Long j = p.getJoursRestants();
        if (j == null) return "Sans date";
        if (j < 0) return "Expiré";
        return j <= daysSpinner.getValue() ? "Bientôt" : "OK";
    }

    private void bind(TableView<Produit> t, FilteredList<Produit> source) {
        SortedList<Produit> sorted = new SortedList<>(source);
        sorted.comparatorProperty().bind(t.comparatorProperty());
        t.setItems(sorted);
    }

    private static String fmt(LocalDate d) { return d == null ? "—" : d.format(DF); }

    private static double num(String s) {
        try { return Double.parseDouble(s.replaceAll("[^0-9.\\-]", "")); }
        catch (Exception e) { return Double.MAX_VALUE; }
    }

    // =============== filtering ===============
    private void applyFilter() {
        String s = stockSearch.getText() == null ? "" : stockSearch.getText().trim().toLowerCase();
        Toggle chip = chipGroup.getSelectedToggle();
        prodFiltered.setPredicate(p -> matches(p, s));
        stockFiltered.setPredicate(p -> matches(p, s) && matchesChip(p, chip));
        updateCount();
    }

    private boolean matches(Produit p, String s) {
        return s.isEmpty()
                || (p.getNom() != null && p.getNom().toLowerCase().contains(s))
                || (p.getCodeBarre() != null && p.getCodeBarre().toLowerCase().contains(s));
    }

    private boolean matchesChip(Produit p, Toggle chip) {
        if (chip == chipLow) return p.isStockFaible();
        if (chip == chipOut) return p.getQuantiteStock() <= 0;
        if (chip == chipExpired) return p.isExpire();
        if (chip == chipSoon) return p.expireDans(7);
        return true;
    }

    // =============== data ===============
    @FXML
    public void refresh() {
        Task<List<Produit>> task = new Task<>() {
            @Override protected List<Produit> call() { return Services.produits.getProduits(); }
        };
        task.setOnSucceeded(e -> {
            all.setAll(task.getValue());
            updateAll();
            lastUpdateLabel.setText("Mis à jour à " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        });
        task.setOnFailed(e -> toast(task.getException().getMessage(), false));
        start(task);
    }

    private void updateAll() {
        updateKpis();
        updateCharts();
        updateAlerts();
        updateExp();
        updateChips();
        applyFilter();
    }

    private void updateCount() {
        stockCountLabel.setText(stockFiltered.size() + " produit(s) affiché(s)");
    }

    private void updateChips() {
        chipAll.setText("Tous (" + all.size() + ")");
        chipLow.setText("Stock faible (" + all.stream().filter(Produit::isStockFaible).count() + ")");
        chipOut.setText("Rupture (" + all.stream().filter(p -> p.getQuantiteStock() <= 0).count() + ")");
        chipSoon.setText("Bientôt expirés (" + all.stream().filter(p -> p.expireDans(7)).count() + ")");
        chipExpired.setText("Expirés (" + all.stream().filter(Produit::isExpire).count() + ")");
    }

    private void updateKpis() {
        int units = all.stream().mapToInt(Produit::getQuantiteStock).sum();
        double value = all.stream().mapToDouble(p -> p.getPrix() * p.getQuantiteStock()).sum();
        long low = all.stream().filter(Produit::isStockFaible).count();
        long out = all.stream().filter(p -> p.getQuantiteStock() <= 0).count();
        long expired = all.stream().filter(Produit::isExpire).count();
        long soon = all.stream().filter(p -> p.expireDans(7)).count();

        kProd.setText(String.valueOf(all.size()));
        kProdSub.setText(units + " unités en stock");
        kVal.setText(String.format(Locale.FRANCE, "%,.0f DA", value));
        kValSub.setText("Prix × quantité");
        kLow.setText(String.valueOf(low));
        kLowSub.setText(out + " en rupture");
        kExp.setText(expired + " / " + soon);
        kExpSub.setText("Expirés / dans 7 jours");
    }

    private void updateCharts() {
        Map<String, Integer> byCat = new TreeMap<>();
        for (Produit p : all)
            byCat.merge(p.getCategorie() == null || p.getCategorie().isEmpty() ? "Autre" : p.getCategorie(),
                    p.getQuantiteStock(), Integer::sum);
        pieChart.setData(FXCollections.observableArrayList(byCat.entrySet().stream()
                .map(e -> new PieChart.Data(e.getKey() + " (" + e.getValue() + ")", e.getValue()))
                .collect(Collectors.toList())));

        XYChart.Series<String, Number> qte = new XYChart.Series<>();
        qte.setName("Quantité");
        XYChart.Series<String, Number> seuil = new XYChart.Series<>();
        seuil.setName("Seuil");
        all.stream()
                .sorted(Comparator.comparingInt(p -> p.getQuantiteStock() - p.getSeuilAlerte()))
                .limit(6)
                .forEach(p -> {
                    String n = p.getNom().length() > 12 ? p.getNom().substring(0, 11) + "…" : p.getNom();
                    qte.getData().add(new XYChart.Data<>(n, p.getQuantiteStock()));
                    seuil.getData().add(new XYChart.Data<>(n, p.getSeuilAlerte()));
                });
        barChart.getData().setAll(qte, seuil);
    }

    private void updateAlerts() {
        List<String> alerts = new ArrayList<>();
        all.stream().filter(Produit::isExpire)
                .forEach(p -> alerts.add("⛔  " + p.getNom() + " : expiré depuis " + (-p.getJoursRestants()) + " j"));
        all.stream().filter(p -> p.expireDans(7))
                .sorted(Comparator.comparing(Produit::getDateExpiration))
                .forEach(p -> alerts.add("⏰  " + p.getNom() + " : "
                        + (p.getJoursRestants() == 0 ? "expire aujourd'hui" : "expire dans " + p.getJoursRestants() + " j")));
        all.stream().filter(Produit::isStockFaible)
                .forEach(p -> alerts.add("📉  " + p.getNom() + " : stock faible (" + p.getQuantiteStock()
                        + " / seuil " + p.getSeuilAlerte() + ")"));
        alertList.getItems().setAll(alerts);
        alertCountLabel.setText(String.valueOf(alerts.size()));
    }

    private void updateExp() {
        int days = daysSpinner.getValue();
        expFiltered.setPredicate(p -> p.isExpire() || p.expireDans(days));
        long expired = all.stream().filter(Produit::isExpire).count();
        long soon = all.stream().filter(p -> p.expireDans(days)).count();
        expInfoLabel.setText(expired + " expiré(s)  •  " + soon + " bientôt");
        expTable.refresh();
    }

    // =============== kpi card ===============
    private VBox kpi(String icon, String title, Label value, Label sub, String color) {
        Label ic = new Label(icon);
        ic.getStyleClass().addAll("kpi-icon", color);
        Label t = new Label(title);
        t.getStyleClass().add("kpi-title");
        value.getStyleClass().add("kpi-value");
        sub.getStyleClass().add("kpi-sub");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(10, t, gap, ic);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(6, top, value, sub);
        box.getStyleClass().addAll("card", "kpi", color + "-card");
        box.setPrefWidth(200);
        box.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    // =============== CRUD ===============
    @FXML private void onAdd() { openForm(null); }

    @FXML
    private void onEdit() {
        Produit p = prodTable.getSelectionModel().getSelectedItem();
        if (p == null) { toast("Sélectionnez d'abord un produit.", false); return; }
        openForm(p);
    }

    @FXML
    private void onDelete() {
        Produit p = prodTable.getSelectionModel().getSelectedItem();
        if (p == null) { toast("Sélectionnez d'abord un produit.", false); return; }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Voulez-vous vraiment supprimer « " + p.getNom() + " » ?", ButtonType.YES, ButtonType.NO);
        a.setHeaderText("Confirmer la suppression");
        App.style(a);
        a.showAndWait().ifPresent(b -> {
            if (b == ButtonType.YES)
                runThenRefresh(() -> Services.produits.deleteProduit(p.getId()), "Produit supprimé");
        });
    }

    private void openForm(Produit p) {
        try {
            FXMLLoader fl = new FXMLLoader(getClass().getResource("/fxml/produit_form.fxml"));
            Parent root = fl.load();
            ProduitFormController c = fl.getController();
            c.setProduit(p);
            Stage s = new Stage();
            s.initModality(Modality.APPLICATION_MODAL);
            s.setTitle(p == null ? "Ajouter un produit" : "Modifier le produit");
            s.setScene(App.scene(root, -1, -1));
            s.setResizable(false);
            s.showAndWait();

            Produit r = c.getResult();
            if (r == null) return;
            if (p == null) runThenRefresh(() -> Services.produits.addProduit(r), "Produit ajouté");
            else {
                r.setId(p.getId());
                runThenRefresh(() -> Services.produits.updateProduit(r), "Produit modifié");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // =============== CSV export ===============
    @FXML
    private void onExport() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Exporter le stock");
        fc.setInitialFileName("stock_" + LocalDate.now() + ".csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Fichier CSV", "*.csv"));
        File f = fc.showSaveDialog(stockTable.getScene().getWindow());
        if (f == null) return;

        StringBuilder sb = new StringBuilder("\uFEFF");   // BOM so Excel reads accents correctly
        sb.append("Code-barres;Produit;Catégorie;Prix;Quantité;Seuil;Expiration;Statut\n");
        for (Produit p : stockTable.getItems()) {
            sb.append(csv(p.getCodeBarre())).append(';')
              .append(csv(p.getNom())).append(';')
              .append(csv(p.getCategorie())).append(';')
              .append(String.format(Locale.US, "%.2f", p.getPrix())).append(';')
              .append(p.getQuantiteStock()).append(';')
              .append(p.getSeuilAlerte()).append(';')
              .append(fmt(p.getDateExpiration())).append(';')
              .append(csv(p.getStatutStock())).append('\n');
        }
        try {
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
            toast("Export réussi : " + f.getName(), true);
        } catch (IOException e) {
            toast("Export impossible : " + e.getMessage(), false);
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // =============== logout ===============
    @FXML
    private void logout() throws IOException {
        Parent root = FXMLLoader.load(getClass().getResource("/fxml/login.fxml"));
        Stage s = (Stage) stockTable.getScene().getWindow();
        s.setMinWidth(0);
        s.setMinHeight(0);
        s.setScene(App.scene(root, -1, -1));
        s.setResizable(false);
        s.sizeToScene();
        s.centerOnScreen();
    }

    // =============== helpers ===============
    private void runThenRefresh(Runnable action, String okMessage) {
        Task<Void> task = new Task<>() {
            @Override protected Void call() { action.run(); return null; }
        };
        task.setOnSucceeded(e -> { toast(okMessage, true); refresh(); });
        task.setOnFailed(e -> toast(task.getException().getMessage(), false));
        start(task);
    }

    private void start(Task<?> task) {
        Thread t = new Thread(task);
        t.setDaemon(true);
        t.start();
    }

    private void toast(String msg, boolean ok) {
        toastLabel.setText((ok ? "✓   " : "✕   ") + msg);
        toastLabel.getStyleClass().removeAll("toast-ok", "toast-err");
        toastLabel.getStyleClass().add(ok ? "toast-ok" : "toast-err");
        if (toastAnim != null) toastAnim.stop();
        toastLabel.setVisible(true);
        FadeTransition in = new FadeTransition(Duration.millis(180), toastLabel);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition wait = new PauseTransition(Duration.seconds(3));
        FadeTransition out = new FadeTransition(Duration.millis(320), toastLabel);
        out.setToValue(0);
        toastAnim = new SequentialTransition(in, wait, out);
        toastAnim.setOnFinished(e -> toastLabel.setVisible(false));
        toastAnim.play();
    }
}
