package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOHashSet;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Scanner;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class RandomActions{
	
	public static void main(String[] args) throws Throwable{
		var methods = Arrays.stream(RandomActions.class.getDeclaredMethods())
		                    .filter(m -> Modifier.isStatic(m.getModifiers()) && !m.getName().contains("main") && !m.getName().contains("lambda"))
		                    .toList();
		String nOrg;
		if(args.length == 0){
			LogUtil.println(methods.stream().map(Method::getName));
			LogUtil.println("Choose method:");
			nOrg = new Scanner(System.in).nextLine().trim();
		}else nOrg = args[0];
		
		var n = nOrg.toLowerCase();
		
		var l = methods.stream().filter(m -> m.getName().equalsIgnoreCase(n)).findAny().map(List::of).orElseGet(
			() -> methods.stream().filter(m -> m.getName().toLowerCase().contains(n)).toList());
		if(l.size() != 1){
			if(l.isEmpty()){
				throw new IllegalArgumentException("No method \"" + nOrg + "\" found");
			}
			throw new IllegalArgumentException(
				"Ambiguous choice \"" + nOrg + "\", possible matches: " +
				l.stream().map(Method::getName).collect(Collectors.joining(", "))
			);
		}
		var meth = l.get(0);
		LogUtil.println(meth);
		var start = Instant.now();
		meth.invoke(null);
		var end = Instant.now();
		var tim = Duration.between(start, end);
		LogUtil.println(
			String.format("Time: %d:%02d:%02d:%03d",
			              tim.toHoursPart(),
			              tim.toMinutesPart(),
			              tim.toSecondsPart(),
			              tim.toMillisPart()
			)
		);
	}
	
	
	private static void treeSet() throws IOException{
		var provider = Cluster.emptyMem();
		var set      = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		var r        = new Random(420);
		
		
		var iter = 50_000_000;
		for(int i = 0; i<iter; i++){
			if(i%(iter/200) == 0) LogUtil.println(i/(float)iter);
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> set.add(num);
				case 1 -> set.remove(num);
				case 2 -> set.contains(num);
			}
		}
	}
	
	
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
			if(i%200000 == 0) LogUtil.println(i/((double)iter));
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> set.add(num);
				case 1 -> set.remove(num);
				case 2 -> set.contains(num);
			}
		}
	}
	
	private static void hashMap() throws IOException{
		var provider = Cluster.emptyMem();
		
		var map = provider.getRootProvider().<HashIOMap<Integer, Integer>>request("hi", HashIOMap.class, Integer.class, Integer.class);
		
		var r    = new Random(69);
		var iter = 50000000;
		for(int i = 0; i<iter; i++){
			if(i%200000 == 0) LogUtil.println(i/((double)iter));
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> map.put(num, r.nextInt(400));
				case 1 -> map.remove(num);
				case 2 -> map.containsKey(num);
			}
		}
	}
	
	private static void hashMapThreadGet() throws IOException{
		var provider = Cluster.emptyMem();
		
		var map = provider.getRootProvider().<HashIOMap<Integer, Integer>>request("hi", HashIOMap.class, Integer.class, Integer.class);
		var ref = new HashMap<Integer, Integer>();
		
		var r = new Random(69);
		
		for(int i : r.ints(0, 5000).distinct().limit(1000).toArray()){
			var v = r.nextInt(5000);
			map.put(i, v);
			ref.put(i, v);
		}
		
		var fac = Thread.ofPlatform().name("worker", 0);
		IntStream.range(0, 20).mapToObj(i -> fac.start(() -> {
			try{
				var rand = new Random(i*10000L);
				var iter = 50000000;
				for(int j = 0; j<iter; j++){
					if(i == 0 && j%(iter/50) == 0) LogUtil.println(j/(double)iter);
					var k = rand.nextInt(5000);
					var a = map.get(k);
					var b = ref.get(k);
					if(!Objects.equals(a, b)){
						LogUtil.println(a, b);
						return;
					}
				}
			}catch(Throwable e){
				e.printStackTrace();
			}
		})).toList().forEach(t -> {
			try{
				t.join();
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
		});
		
	}
	
}
