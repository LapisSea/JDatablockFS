package test.ui;

import com.lapissea.util.LogUtil;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import javafx.scene.Scene;

public class UITest extends Application{
	
	public static void main(String[] args){ launch(args); }
	
	@Override
	public void start(Stage primaryStage) throws Exception{
		
		primaryStage.setTitle("CSF UI Test");
		
		Scene scene=new Scene(FXMLLoader.load(Objects.requireNonNull(getClass().getResource("/view/Ui.fxml"))));
		
		primaryStage.setScene(scene);
		primaryStage.show();
	}
	
	
}
