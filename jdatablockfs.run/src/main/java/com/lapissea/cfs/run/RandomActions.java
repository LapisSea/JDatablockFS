package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOHashSet;
import com.lapissea.cfs.utils.RawRandom;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeRunnable;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@SuppressWarnings("unused")
public final class RandomActions{
	
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
	
	
	private static <E extends Throwable> void runIter(int iter, int period, UnsafeRunnable<E> run) throws E{
		Instant start = null;
		int     pLen  = 0;
		for(int i = 0; i<iter; i++){
			
			run.run();
			
			if(i<period){
				if(i == 0) LogUtil.println("First");
				else if(i%(period/10) == 0) LogUtil.println(i/(double)period);
			}else if(i%period == 0){
				if(start == null){
					start = Instant.now();
					continue;
				}
				var i0      = i - period;
				var percent = i0/((double)iter);
				var pass    = Duration.between(start, Instant.now()).toMillis();
				var etr     = Duration.ofMillis((long)(pass/percent - pass));
				var ps      = percent + "";
				pLen = Math.max(pLen, ps.length());
				LogUtil.println(
					percent + "%, " + " ".repeat(pLen - ps.length()) +
					String.format("ETR: %d:%02d:%02d:%03d",
					              etr.toHoursPart(),
					              etr.toMinutesPart(),
					              etr.toSecondsPart(),
					              etr.toMillisPart()
					) + ", " +
					String.format("ops/ms: %.2f", (i0/(double)pass))
				);
			}
		}
	}
	
	private static void treeSet() throws IOException{
		var provider = Cluster.emptyMem();
		var set      = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		
		var r    = new RawRandom(420);
		var iter = 50_000_000;
		runIter(iter, iter/200, () -> {
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> set.add(num);
				case 1 -> set.remove(num);
				case 2 -> set.contains(num);
			}
		});
	}
	
	private static int idx(RawRandom r, List<Chunk> ch){
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
		
		var r = new RawRandom(69);
		runIter(5000000, 100000, () -> {
			if(chs.isEmpty() || r.nextBoolean()){
				var t = AllocateTicket.bytes(r.nextInt(50));
				if(r.nextBoolean()){
					t = t.withPositionMagnet(provider.getSource().getIOSize());
				}
				for(int i1 = 0; i1<chs.size(); i1++){
					if(chs.get(i1) == null){
						chs.set(i1, t.submit(provider));
						return;
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
		});
	}
	
	private static void hashSet() throws IOException{
		var provider = Cluster.emptyMem();
		
		var set = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		var r = new RawRandom(69);
		runIter(50000000, 200000, () -> {
			Integer num = r.nextInt(400);
			switch(r.nextInt(3)){
				case 0 -> set.add(num);
				case 1 -> set.remove(num);
				case 2 -> set.contains(num);
			}
		});
	}
	
	private static void hashMap() throws IOException{
		var provider = Cluster.emptyMem();
		
		var map = provider.getRootProvider().<HashIOMap<Integer, Integer>>request("hi", HashIOMap.class, Integer.class, Integer.class);
		
		var r = new RawRandom(69);
		runIter(200000000, 1000000, () -> {
			Integer num = r.nextInt(400);
			switch(r.nextInt(4)){
				case 0 -> map.put(num, r.nextInt(400));
				case 1 -> map.remove(num);
				case 2 -> map.containsKey(num);
				case 3 -> map.get(num);
			}
		});
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
	
	private static void numberIO(){
		record Err(Throwable e, int th, int iter, long l){ }
		
		Err[] err = {new Err(null, 0, 0, Long.MAX_VALUE)};
		
		var count = new AtomicLong();
		var th    = Runtime.getRuntime().availableProcessors() + 1;
		var iter  = 500_000_000;
		var total = iter*(long)th;
		IntStream.range(0, th).mapToObj(ti -> Thread.ofPlatform().start(() -> {
			var r = new RawRandom(69L*ti);
			try(var io = MemoryData.builder().withCapacity(8).withUsedLength(8).build().io()){
				for(int i = 0; i<iter; i++){
					if(i%100 == 0){
						var c = count.incrementAndGet()*100;
						if(c%(total/100) == 0){
							LogUtil.println(c/(double)total);
						}
					}
					{
						var l = i == 0? -6 : r.nextInt();
						try{
							var size = NumberSize.bySizeSigned(l);
							size.writeIntSigned(io.setPos(0), l);
							var lr = size.readIntSigned(io.setPos(0));
							if(l != lr){
								throw new AssertionError(l + "!=" + lr);
							}
						}catch(Throwable e){
							synchronized(NumberSize.class){
								e.printStackTrace();
								if(Math.abs(err[0].l)>Math.abs(l)){
									err[0] = new Err(e, ti, i, l);
								}
							}
						}
					}
					
					var l = i == 0? -6 : r.nextLong();
					try{
						var size = NumberSize.bySizeSigned(l);
						size.writeSigned(io.setPos(0), l);
						var lr = size.readSigned(io.setPos(0));
						if(l != lr){
							throw new AssertionError(l + "!=" + lr);
						}
					}catch(Throwable e){
						synchronized(NumberSize.class){
							if(Math.abs(err[0].l)>Math.abs(l)){
								err[0] = new Err(e, ti, i, l);
							}
						}
					}
				}
			}
		})).toList().forEach(thread -> {
			try{
				thread.join();
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
		});
		if(err[0].e != null){
			LogUtil.println(err[0]);
			err[0].e.printStackTrace();
		}
	}
}
