package com.lapissea.dfs.run.world;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
public class InventorySlot extends IOInstance.Managed<InventorySlot>{
	
	@IODependency.VirtualNumSize
	public int id    = 69;
	@IODependency.VirtualNumSize
	public int count = 420;
	
	public InventorySlot(){ }
	public InventorySlot(int id, int count){
		this.id = id;
		this.count = count;
	}
}
