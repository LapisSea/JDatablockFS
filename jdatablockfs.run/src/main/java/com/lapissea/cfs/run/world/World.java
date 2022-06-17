package com.lapissea.cfs.run.world;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.LogUtil;
import com.lapissea.util.Rand;

import java.io.IOException;

public class World{
	
	
	public static void main(String[] args) throws IOException{
		var smap=Struct.of(Map.class);
		
		var data=MemoryData.builder().build();
		Cluster.init(data);
		var cluster=new Cluster(data);
		
		var map=cluster.getRootProvider().request(smap, "map");
		
		map.entities.addMultipleNew(20, e->{
			e.pos.x=(Rand.f()-0.5F)*20;
			e.pos.y=(Rand.f()-0.5F)*20;
		});
		
		LogUtil.println(map.toString(false, "{\n\t", "\n}", " = ", ",\n\t"));
	}
	
}
