package com.lapissea.cfs.run.world;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.Rand;

import java.io.IOException;

public class World{
	
	
	public static void main(String[] args) throws IOException{
		LoggedMemoryUtils.simpleLoggedMemorySession(mem->{
			var map=Cluster.init(mem).getRootProvider().request("map", Map.class);
			
			map.entities.addMultipleNew(20, e->{
				e.pos.x=(Rand.f()-0.5F)*20;
				e.pos.y=(Rand.f()-0.5F)*20;
				e.inventory.add(new InventorySlot());
			});
			map.entities.modify(5, e->{
				e.inventory.add(new InventorySlot());
				return e;
			});
			map.entities.modify(5, e->{
				e.inventory.add(new InventorySlot());
				return e;
			});
			map.entities.modify(5, e->{
				e.inventory.clear();
				return e;
			});
		});
	}
	
	
}
