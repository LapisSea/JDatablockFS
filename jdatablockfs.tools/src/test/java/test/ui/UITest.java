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
	
	public static void main(String[] args) throws IOException{
		LogUtil.println(UITest.class.getResource("view/Ui.fxml"));
//		launch(args);
	}
	
	@Override
	public void start(Stage primaryStage) throws Exception{
		URL res=getClass().getResource("test/ui/view/Ui.fxml");
		Objects.requireNonNull(res);
		Parent root=FXMLLoader.load(res);
		
		primaryStage.setTitle("Hello World!");
		
		primaryStage.setScene(new Scene(root));
		primaryStage.show();
	}
	
	
}
