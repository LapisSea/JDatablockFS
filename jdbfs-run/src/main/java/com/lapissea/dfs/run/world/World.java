package com.lapissea.dfs.run.world;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.stream.IntStream;

public final class World{
	
	public static void main(String[] args) throws IOException{
		LoggedMemoryUtils.simpleLoggedMemorySession(mem -> {
			var map = Cluster.init(mem).roots().request("map", Map.class);
			var r   = new RawRandom(69);
			for(int x = 0; x<16; x++){
				for(int y = 0; y<16; y++){
					var ch = Chunk.newAt(x, y);
					ch.entities().addAll(IntStream.range(0, (int)(Math.pow(r.nextFloat(), 4)*100)).mapToObj(i -> {
						var e = new Entity();
						e.pos.x = r.nextFloat();
						e.pos.y = r.nextFloat();
						e.inventory = IntStream.range(0, r.nextInt(5))
						                       .mapToObj(j -> new InventorySlot())
						                       .toList();
						return e;
					}).toList());
					map.chunks.add(ch);
				}
			}
			LogUtil.println(mem.getIOSize(), "bytes used");
		});
	}
	
	
}
