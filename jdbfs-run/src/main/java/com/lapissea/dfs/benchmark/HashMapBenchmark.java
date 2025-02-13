package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.utils.RawRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Warmup(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 200, time = 50, timeUnit = TimeUnit.MILLISECONDS)
@Fork(5)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class HashMapBenchmark{
	
	public static void main(String[] args) throws Exception{
		var opt  = new OptionsBuilder().include(HashMapBenchmark.class.getSimpleName());
		var mode = args.length>=1? args[0] : "";
		if(mode.equals("json")){
			opt.resultFormat(ResultFormatType.JSON);
			var date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm_dd-MM-yyyy"));
			opt.result("benchmarks/" + HashMapBenchmark.class.getSimpleName() + " " + date + ".json");
		}
		new Runner(opt.build()).run();
	}
	
	@State(Scope.Thread)
	public static class Premade{
		
		public enum KeySet{
			RANDOM,
			MISS_ONLY,
			MATCH_ONLY
		}
		
		record Inst(Cluster cluster, IOMap<Integer, Integer> map, List<Integer> sampleValues, RandomGenerator rand){
			private int nextKey(){
				return sampleValues.get(rand.nextInt(sampleValues.size()));
			}
		}
		
		@Param({"5", "50", "300"})
		public int    initSize;
		@Param({"2", "5"})
		public int    rangeMul;
		@Param
		public KeySet keySet;
		
		private int range;
		
		private Inst       theInst;
		private List<Inst> instances;
		
		@Setup(Level.Iteration)
		public void init() throws IOException{
			range = initSize*rangeMul;
			theInst = make();
			
			var inst = System.getProperty("instances");
			if(inst != null){
				instances = IntStream.range(0, Integer.parseInt(inst)).parallel().mapToObj(i -> {
					try{
						var                     cl  = new Cluster(MemoryData.builder().withData(theInst.cluster.getSource()).build());
						IOMap<Integer, Integer> map = cl.roots().require(new ObjectID.LID(1), HashIOMap.class, Integer.class, Integer.class);
						return new Inst(cl, map, theInst.sampleValues, new RawRandom());
					}catch(IOException e){
						throw new RuntimeException(e);
					}
				}).toList();
			}
		}
		private Inst make() throws IOException{
			var                     provider = Cluster.emptyMem();
			IOMap<Integer, Integer> map      = provider.roots().request(1, HashIOMap.class, Integer.class, Integer.class);
			
			var rand = new RawRandom();
			var vals = rand.ints(0, range).distinct().limit(initSize).boxed()
			               .collect(Collectors.toMap(i -> i, i -> rand.nextInt(range)));
			map.putAll(vals);
			var sampleValues = switch(keySet){
				case RANDOM -> IntStream.range(0, range).boxed().toList();
				case MISS_ONLY -> IntStream.range(0, range).filter(i -> !vals.containsKey(i)).boxed().toList();
				case MATCH_ONLY -> IntStream.range(0, range).filter(vals::containsKey).boxed().toList();
			};
			return new Inst(provider, map, sampleValues, rand);
		}
		
		private int pos;
		Inst pick(){
			var p  = pos;
			var p1 = p + 1;
			if(p1>=instances.size()){
				p1 = 0;
			}
			pos = p1;
			return instances.get(p);
		}
	}
	
	@State(Scope.Thread)
	public static class ToFill{
		
		private IOMap<Integer, Integer> map;
		
		@Setup(Level.Invocation)
		public void init() throws IOException{
			var provider = Cluster.emptyMem();
			map = provider.roots().request(1, HashIOMap.class, Integer.class, Integer.class);
			map.put(1, 1);
			map.remove(1);
		}
		
	}
	
	@Benchmark
	@Warmup(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
	@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
	@OperationsPerInvocation(4000)
	public void fill(ToFill toFill) throws IOException{
		for(int i = 0; i<4000; i++){
			toFill.map.put((int)toFill.map.size(), 1);
		}
	}
	
	@Benchmark
	public Object get(Premade premade) throws IOException{
		var inst = premade.theInst;
		return inst.map.get(inst.nextKey());
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Dinstances=500")
	public void put(Premade premade) throws IOException{
		var inst = premade.pick();
		inst.map.put(inst.nextKey(), inst.rand.nextInt(100));
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Dinstances=500")
	public boolean remove(Premade premade) throws IOException{
		var inst = premade.pick();
		return inst.map.remove(inst.nextKey());
	}
	
	@Benchmark
	public Object contains(Premade premade) throws IOException{
		var inst = premade.theInst;
		return inst.map.get(inst.nextKey());
	}
}
