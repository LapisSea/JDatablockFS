package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

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
