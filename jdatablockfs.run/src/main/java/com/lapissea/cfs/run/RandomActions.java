package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.objects.collections.IOHashSet;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RandomActions{
	
	
	private static int idx(Random r, List<Chunk> ch){
		int index = r.nextInt(ch.size());
		for(int i = 0; i<ch.size(); i++){
			var j = (i + index)%ch.size();
			if(ch.get(j) != null){
				return j;
			}
		}
		return -1;
	}
	
	private static void alloc() throws IOException{
		var provider = Cluster.emptyMem();
		
		List<Chunk> chs = new ArrayList<>();
		var         r   = new Random(69);
		a:
		for(int i = 0; i<5000000; i++){
			if(i%100000 == 0) LogUtil.println(i/5000000D, chs.size(), provider.getMemoryManager().getFreeChunks().size());
			if(chs.isEmpty() || r.nextBoolean()){
				var t = AllocateTicket.bytes(r.nextInt(50));
				if(r.nextBoolean()){
					t = t.withPositionMagnet(provider.getSource().getIOSize());
				}
				for(int i1 = 0; i1<chs.size(); i1++){
					if(chs.get(i1) == null){
						chs.set(i1, t.submit(provider));
						continue a;
					}
				}
				chs.add(t.submit(provider));
			}else{
				var id = idx(r, chs);
				var ch = chs.get(id);
				if(id == chs.size() - 1){
					chs.remove(id);
					while(!chs.isEmpty() && chs.get(chs.size() - 1) == null) chs.remove(chs.size() - 1);
				}else chs.set(id, null);
				
				provider.getMemoryManager().free(ch);
			}
		}
	}
	
	private static void hashSet() throws IOException{
		var provider = Cluster.emptyMem();
		
		var set = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		var r    = new Random(69);
		var iter = 50000000;
		for(int i = 0; i<iter; i++){
			if(i%100000 == 0) LogUtil.println(i/((double)iter));
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> {
					var added1 = set.add(num);
				}
				case 1 -> {
					var removed1 = set.remove(num);
				}
				case 2 -> {
					var c1 = set.contains(num);
				}
			}
		}
	}
	
}
