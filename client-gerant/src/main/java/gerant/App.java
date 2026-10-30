package gerant;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;

public class App extends Application {

    /** Creates a scene with the app stylesheet. Pass w = -1 to use the root's preferred size. */
    public static Scene scene(Parent root, double w, double h) {
        Scene s = (w < 0) ? new Scene(root) : new Scene(root, w, h);
        s.getStylesheets().add(App.class.getResource("/css/style.css").toExternalForm());
        return s;
    }

    public static void style(Dialog<?> d) {
        d.getDialogPane().getStylesheets().add(App.class.getResource("/css/style.css").toExternalForm());
    }

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(App.class.getResource("/fxml/login.fxml"));
        stage.setTitle("SuperGest - Espace Gérant");
        stage.getIcons().add(new javafx.scene.image.Image(App.class.getResourceAsStream("/img/logo.png")));
        stage.setScene(scene(root, -1, -1));
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}