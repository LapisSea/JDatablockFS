package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 50, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(20)
public class EncodingBenchmark{
	
	public static void main(String[] args) throws RunnerException{
		var options = new OptionsBuilder().include(EncodingBenchmark.class.getSimpleName());
		if(Iters.of(args).anyEquals("profile")){
			options.forks(0).measurementTime(new TimeValue(1000, TimeUnit.MILLISECONDS));
		}
		new Runner(options.build()).run();

//		var b = new EncodingBenchmark();
//
//		var bh = new Blackhole("Today's password is swordfish. I understand instantiating Blackholes directly is dangerous.");
//		b.setup();
//		for(int i = 0; i<10000000; i++){
//			bh.consume(b.findBest());
//		}
	}
	
	private List<String> testStrings;
	
	private final RandomGenerator random = new RawRandom();
	
	@Setup(Level.Iteration)
	public void setup(){
//		testStrings = Iters.of(AutoText.class, String.class, Encoding.class, Cluster.class, Iters.class, UtilL.class).toList(Class::getName);
		testStrings = Iters.from(Encoding.values()).toList(e -> e.format.randomString(random, 50, 50));
	}
	
	private String getRandomTestString(){
		return testStrings.get(random.nextInt(testStrings.size()));
	}
	
	@Benchmark
	public Encoding findBest(){
		String testString = getRandomTestString();
		return Encoding.findBest(testString);
	}
}
