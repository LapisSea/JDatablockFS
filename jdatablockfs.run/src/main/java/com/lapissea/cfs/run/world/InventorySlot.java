package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class InventorySlot extends IOInstance<InventorySlot>{
	
	@IOValue
	@IODependency.VirtualNumSize
	public int id=69;
	
	@IOValue
	@IODependency.VirtualNumSize
	public int count=420;
	
}
