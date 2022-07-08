package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.util.ArrayList;
import java.util.List;

public class Entity extends IOInstance<Entity>{
	
	@IOValue
	public Vec2d pos=new Vec2d();
	
	@IOValue
	@IOValue.Reference
	public List<InventorySlot> inventory=new ArrayList<>();
	
}
