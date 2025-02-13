package demos.world;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
public class InventorySlot extends IOInstance.Managed<InventorySlot>{
	
	public int id    = 69;
	public int count = 420;
	
	public InventorySlot(){ }
	public InventorySlot(int id, int count){
		this.id = id;
		this.count = count;
	}
}
