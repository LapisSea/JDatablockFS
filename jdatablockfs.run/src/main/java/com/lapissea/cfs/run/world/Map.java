package com.lapissea.cfs.run.world;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class Map extends IOInstance<Map>{
	
	@IOValue
	private IOList<Entity> entities;
	
	
	
}
