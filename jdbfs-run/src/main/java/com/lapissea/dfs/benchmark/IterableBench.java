package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


@Warmup(iterations = 8, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(2)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IterableBench{
	public record Value(int a){ }
	
	public static void main(String[] args) throws RunnerException{
		var builder = new OptionsBuilder().include(IterableBench.class.getSimpleName());
		
		if(Iters.from(args).anyEquals("noForks"))
			builder.forks(0);
		
		new Runner(builder.build()).run();
	}
	
	@State(Scope.Thread)
	public static class BenchmarkState{
		@Param({"10", "1000", "100000"})
		public int size;
		
		public List<Value> values;
		
		@Setup(Level.Trial)
		public void setUp(){
			values = IntStream.range(0, size)
			                  .mapToObj(Value::new)
			                  .collect(Collectors.toList());
		}
	}
	
	private final RawRandom rand = new RawRandom();
	
	@Benchmark
	public void stream_findFirst(BenchmarkState state, Blackhole blackhole){
		var   toFind = rand.nextInt(state.size);
		Value result = state.values.stream().filter(v -> v.a == toFind).findFirst().orElse(null);
		blackhole.consume(result);
	}
	@Benchmark
	public void iterpp_findFirst(BenchmarkState state, Blackhole blackhole){
		var   toFind = rand.nextInt(state.size);
		Value result = Iters.from(state.values).filtered(v -> v.a == toFind).findFirst().orElse(null);
		blackhole.consume(result);
	}
	@Benchmark
	public void iterpp_findFirst_v2(BenchmarkState state, Blackhole blackhole){
		var   toFind = rand.nextInt(state.size);
		Value result = Iters.from(state.values).firstMatching(v -> v.a == toFind).orElse(null);
		blackhole.consume(result);
	}
	
	//	@Benchmark
	public void byHand_findFirst(BenchmarkState state, Blackhole blackhole){
		var   toFind = rand.nextInt(state.size);
		Value result = findFirstHand(state, toFind);
		blackhole.consume(result);
	}
	
	private static Value findFirstHand(BenchmarkState state, int toFind){
		for(Value value : state.values){
			if(value.a == toFind){
				return value;
			}
		}
		return null;
	}
}
