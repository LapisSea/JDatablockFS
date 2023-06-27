package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.util.LogUtil;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class HashMapBenchmark{
	public static void main(String[] args) throws Throwable{
		manual();
	}
	
	private static void manual() throws IOException{
		var b = new HashMapBenchmark();
		b.initSize = 500;
		b.rangeMul = 2;
		
		b.init();
		for(int i = 0; i<100000; i++){
			if(i%1000 == 0) LogUtil.println(i/100000D);
			b.get();
		}
	}
	
	private final Random rand = new Random();
	
	@Param({"5", "50", "500"})
	public int initSize;
	@Param({"1", "2", "5"})
	public int rangeMul;
	
	private int                     range;
	private IOMap<Integer, Integer> map;
	
	
	@Setup(Level.Invocation)
	public void init() throws IOException{
		range = Math.max(initSize, 1)*rangeMul;
		
		var provider = Cluster.emptyMem();
		map = provider.getRootProvider().request("hi", HashIOMap.class, Integer.class, Integer.class);
		
		var rand = new Random();
		map.putAll(
			rand.ints(0, range).distinct().limit(initSize).boxed()
			    .collect(Collectors.toMap(i -> i, i -> rand.nextInt(range)))
		);
	}
	
	@Benchmark
	@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
	public void get() throws IOException{
		map.get(rand.nextInt(range));
	}
	
	@Benchmark
	@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
	public void put() throws IOException{
		map.put(rand.nextInt(range), rand.nextInt(range));
	}
}
