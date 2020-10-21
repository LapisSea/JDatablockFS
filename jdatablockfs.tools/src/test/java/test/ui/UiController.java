package test.ui;

import com.lapissea.cfs.cluster.extensions.BlockMapCluster;
import com.lapissea.cfs.exceptions.IllegalKeyException;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.objects.text.AutoText;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class UiController{
	
	public TextField        text;
	public TextField        name;
	public ListView<String> items;
	
	public Button addBtn;
	public Button setBtn;
	public Button deleteBtn;
	
	BlockMapCluster<AutoText> cluster;
	
	{
		try{
			cluster=ClusterStore.start(AutoText.class);
		}catch(IOException e){
			e.printStackTrace();
		}
	}
	
	public void initialize() throws IOException{
		updateItems();
		items.getSelectionModel().selectedItemProperty().addListener(o->updateSelect());
		items.getSelectionModel().select(0);
		name.textProperty().addListener(o->{
			var lastIndex=items.getSelectionModel().getSelectedIndex();
			for(int i=1;i<items.getItems().size();i++){
				if(items.getItems().get(i).equals(name.getText())){
					if(items.getSelectionModel().getSelectedIndex()!=i){
						items.getSelectionModel().select(i);
					}
					return;
				}
			}
			if(lastIndex>0){
				var t=name.getText();
				items.getSelectionModel().select(0);
				text.clear();
				name.setText(t);
			}
		});
	}
	
	private void updateSelect(){
		int index=items.getSelectionModel().getSelectedIndex();
		
		addBtn.setDisable(index>0);
		setBtn.setDisable(index<=0);
		deleteBtn.setDisable(index<=0);
		
		if(index<=0){
			name.setText("");
			text.setText("");
		}else{
			var key=items.getItems().get(index);
			if(!key.equals(name.getText())) name.setText(key);
			try{
				var val=new String(cluster.openBlock(new AutoText(key), RandomIO.Mode.READ_ONLY, ContentReader::readRemaining));
				text.setText(val);
			}catch(Throwable e){
				e.printStackTrace();
			}
		}
		
	}
	
	private void updateItems() throws IOException{
		
		int index=items.getSelectionModel().getSelectedIndex();
		
		items.getItems().clear();
		List<String> items=new ArrayList<>(cluster.size());
		items.add("<new>");
		cluster.listKeys(e->items.add(e.getData()));
		this.items.getItems().addAll(items);
		this.items.getSelectionModel().select(index);
	}
	
	public void add() throws IOException{
		var key=new AutoText(name.getText().trim());
		cluster.defineBlock(key, io->{
			io.writeInts1(text.getText().getBytes());
			io.trim();
		});
		updateItems();
	}
	
	public void delete() throws IOException{
		
		int index=items.getSelectionModel().getSelectedIndex();
		
		cluster.deleteBlock(new AutoText(name.getText().trim()));
		updateItems();
		this.items.getSelectionModel().select(Math.min(items.getItems().size()-1, index));
	}
	
	public void set() throws IOException{
		var key=new AutoText(name.getText().trim());
		try{
			cluster.openBlock(key, RandomIO.Mode.READ_WRITE, io->{
				io.writeInts1(text.getText().getBytes());
				io.trim();
			});
		}catch(IllegalKeyException e){ }
		updateItems();
	}
	
	public void pack() throws IOException{
		cluster.pack();
	}
}
