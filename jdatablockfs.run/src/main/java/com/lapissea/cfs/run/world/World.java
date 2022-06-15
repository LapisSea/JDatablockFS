package com.lapissea.cfs.run.world;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.LogUtil;

import java.io.IOException;

public class World{
	
	
	public static void main(String[] args) throws IOException{
		var smap=Struct.of(Map.class);
		
		var data=MemoryData.build().build();
		Cluster.init(data);
		var cluster=new Cluster(data);
		
		var map=cluster.getRootProvider().request(smap, "map");
		
		var e=map.entities.addNew();
		e.pos.x=69;
		e.pos.y=420;
		
		LogUtil.println(map);
	}
	
}
