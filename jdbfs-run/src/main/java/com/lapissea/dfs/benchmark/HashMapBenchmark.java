package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.utils.RawRandom;
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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(5)
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class HashMapBenchmark{
	
	public static void main(String[] args) throws Exception{
//		if(args.length == 0){
//			System.out.print("Enter mode: ");
//			args = new Scanner(System.in).nextLine().trim().split(" ", 2);
//		}
		var opt  = new OptionsBuilder().include(HashMapBenchmark.class.getSimpleName());
		var mode = args.length>=1? args[0] : "";
		if(mode.equals("json")){
			opt.resultFormat(ResultFormatType.JSON);
			var date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm_dd-MM-yyyy"));
			opt.result("benchmarks/" + HashMapBenchmark.class.getSimpleName() + " " + date + ".json");
		}
		new Runner(opt.build()).run();
	}
	
	public enum KeySet{
		RANDOM,
		MISS_ONLY,
		MATCH_ONLY
	}
	
	private final RawRandom rand = new RawRandom();
	
	@Param({"5", "50", "500"})
	public int    initSize;
	@Param({"2", "5"})
	public int    rangeMul;
	@Param
	public KeySet keySet;
	
	private int                     range;
	private IOMap<Integer, Integer> map;
	
	private List<Integer> sampleValues;
	
	
	@Setup(Level.Iteration)
	public void init() throws IOException{
		range = initSize*rangeMul;
		
		var provider = Cluster.emptyMem();
		map = provider.roots().request(1, HashIOMap.class, Integer.class, Integer.class);
		
		var rand = new RawRandom();
		var vals = rand.ints(0, range).distinct().limit(initSize).boxed()
		               .collect(Collectors.toMap(i -> i, i -> rand.nextInt(range)));
		map.putAll(vals);
		sampleValues = switch(keySet){
			case RANDOM -> IntStream.range(0, range).boxed().toList();
			case MISS_ONLY -> IntStream.range(0, range).filter(i -> !vals.containsKey(i)).boxed().toList();
			case MATCH_ONLY -> IntStream.range(0, range).filter(vals::containsKey).boxed().toList();
		};
	}
	
	private Integer nextKey(){
		return sampleValues.get(rand.nextInt(sampleValues.size()));
	}
	
	@Benchmark
	public void get() throws IOException{
		map.get(nextKey());
	}
	
	//Setup has to be Level.Invocation
	@Benchmark
	public void put() throws IOException{
		map.put(nextKey(), rand.nextInt(100));
	}
	
	@Benchmark
	public void remove() throws IOException{
		map.remove(nextKey());
	}
	
	@Benchmark
	public void contains() throws IOException{
		map.containsKey(nextKey());
	}
}
