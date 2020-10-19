package test.ui;

import com.lapissea.cfs.cluster.AllocateTicket;
import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.TypeParser;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.objects.IOType;
import com.lapissea.cfs.objects.UserInfo;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.text.AutoText;
import com.lapissea.util.LogUtil;
import javafx.event.ActionEvent;
import javafx.scene.control.TextField;

import java.io.IOException;
import java.util.Objects;

public class UiController{
	
	static class Entry extends IOInstance{
		@IOStruct.Value(index=0, rw=AutoText.StringIO.class)
		String name;
		
		@IOStruct.Value(index=1, rw=ChunkPointer.FixedIO.class)
		ChunkPointer data;
		
		public Entry(){
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			if(!(o instanceof Entry)) return false;
			Entry entry=(Entry)o;
			return Objects.equals(name, entry.name)&&
			       Objects.equals(data, entry.data);
		}
		
		@Override
		public int hashCode(){
			return Objects.hash(name, data);
		}
	}
	
	public TextField text;
	public TextField name;
	Cluster cluster;
	
	{
		try{
			cluster=ClusterStore.start();
			cluster.getTypeParsers().register(TypeParser.rawExact(IOStruct.get(Entry.class)));
		}catch(IOException e){
			e.printStackTrace();
		}
	}
	
	
	public void execute(ActionEvent actionEvent) throws IOException{
		for(UserInfo userChunk : cluster.getUserChunks()){
			Entry entry=(Entry)userChunk.getObjPtr().read(cluster);
			if(entry.name.equals(name.getText().trim())){
				cluster.getChunk(entry.data).io(io->{
					io.write(text.getText().getBytes());
					io.trim();
				});
				return;
			}
		}
		AllocateTicket.user(new IOType(Entry.class)).submit(cluster).io(io->io.write(text.getText().getBytes()));
	}
	
	public void delete(ActionEvent actionEvent) throws IOException{
		for(UserInfo userChunk : cluster.getUserChunks()){
			
			Entry entry=(Entry)userChunk.getObjPtr().read(cluster);
			if(entry.name.equals(name.getText().trim())){
				cluster.getUserChunks().removeElement(userChunk);
				cluster.getChunk(entry.data).freeChaining();
				return;
			}
		}
	}
}
