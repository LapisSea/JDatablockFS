package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class Entity extends IOInstance<Entity>{
	
	@IOValue
	private Vec2d pos;
	
}
