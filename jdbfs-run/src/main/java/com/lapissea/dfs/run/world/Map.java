package com.lapissea.dfs.run.world;

import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
public class Map extends IOInstance.Managed<Map>{
	public IOList<Chunk> chunks;
}
