package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.objects.collections.IOSet;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;

public class HashBucketBoundaryTests{
	
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
	private static final int MIN_HASH_KEY = 0x7EC69360;
	
	private static int mixMurmur32(int z){
		z = (z^(z >>> 16))*0x85ebca6b;
		z = (z^(z >>> 13))*0xc2b2ae35;
		return z^(z >>> 16);
	}
	
	@Test
	public void keyHashesToMinimumInteger(){
		Assert.assertEquals(mixMurmur32(MIN_HASH_KEY), Integer.MIN_VALUE);
	}
	
	@Test
	public void mapStoresMinimumHashAfterGrowth() throws IOException{
		var cl      = Cluster.emptyMem();
		var typeDef = IOType.of(HashIOMap.class, Integer.class, Integer.class);
		var chunk   = AllocateTicket.bytes(64).submit(cl);
		var map     = new HashIOMap<Integer, Integer>(cl, chunk, typeDef);
		
		// Three filler keys fill the initial capacity-4 set; the next put grows it to 6
		// ((long)(4 * 1.618)), the first capacity for which MIN_VALUE % capacity is negative.
		map.put(1, 10);
		map.put(2, 20);
		map.put(3, 30);
		map.put(MIN_HASH_KEY, 99);
		
		Assert.assertEquals(map.getEntry(MIN_HASH_KEY).getValue(), 99, "minimum-hash key must be readable");
		Assert.assertTrue(map.containsKey(MIN_HASH_KEY), "minimum-hash key must be found");
		Assert.assertEquals(map.size(), 4L, "all four entries must be stored");
	}
	
	@Test
	public void setStoresMinimumHash() throws IOException{
		var cl  = Cluster.emptyMem();
		var set = cl.roots().<IOSet<Integer>>request(1, IOHashSet.class, Integer.class);
		
		set.add(1);
		set.add(2);
		set.add(MIN_HASH_KEY);
		
		Assert.assertTrue(set.contains(MIN_HASH_KEY), "minimum-hash key must be in the set");
		Assert.assertEquals(set.size(), 3L, "all three elements must be stored");
	}
}
