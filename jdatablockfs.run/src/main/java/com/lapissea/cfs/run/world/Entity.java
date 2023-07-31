package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.util.ArrayList;
import java.util.List;

@IOValue
public class Entity extends IOInstance.Managed<Entity>{
	public Vec2d               pos       = new Vec2d();
	public List<InventorySlot> inventory = new ArrayList<>();
}
