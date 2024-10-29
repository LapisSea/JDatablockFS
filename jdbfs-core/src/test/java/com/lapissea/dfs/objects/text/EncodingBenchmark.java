package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
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
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 40, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(10)
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
	
	public enum EncodingVal{
		ALL(Encoding.values()),
		BASE_16_UPPER(Encoding.BASE_16_UPPER),
		BASE_16_LOWER(Encoding.BASE_16_LOWER),
		BASE_32_UPPER(Encoding.BASE_32_UPPER),
		BASE_32_LOWER(Encoding.BASE_32_LOWER),
		BASE_64(Encoding.BASE_64),
		BASE_64_CNAME(Encoding.BASE_64_CNAME),
		LATIN1(Encoding.LATIN1),
		UTF8(Encoding.UTF8);
		
		final Encoding[] values;
		EncodingVal(Encoding... values){ this.values = values; }
	}
	
	private List<Encoding> encodings;
	private List<String>   testStrings;
	private int[]          byteSizes;
	private List<byte[]>   encodedData;
	
	private final RandomGenerator random = new RawRandom();
	
	@Param
	private EncodingVal encoding;
	
	@Setup(Level.Iteration)
	public void setup(){
		encodings = List.of(encoding.values);
		
		testStrings = Iters.from(encodings).toList(e -> e.format.randomString(random, 50, 50));
		byteSizes = Iters.zip(encodings, testStrings).mapToInt(e -> e.getKey().calcSize(e.getValue())).toArray();
		encodedData = Iters.zip(encodings, testStrings).map(e -> {
			var dest = new ContentOutputBuilder();
			try{
				e.getKey().write(dest, e.getValue());
			}catch(IOException ex){
				throw new RuntimeException(ex);
			}
			return dest.toByteArray();
		}).toList();
	}
	
	@Benchmark
	public ContentOutputBuilder encode() throws IOException{
		var i = random.nextInt(testStrings.size());
		
		var testString = testStrings.get(i);
		var encoding   = encodings.get(i);
		
		var dest = new ContentOutputBuilder(byteSizes[i]);
		encoding.write(dest, testString);
		return dest;
	}
	
	@Benchmark
	public StringBuilder decode() throws IOException{
		var i = random.nextInt(testStrings.size());
		
		var chars    = testStrings.get(i).length();
		var encoding = encodings.get(i);
		
		var dest = new StringBuilder(chars);
		encoding.read(new ContentInputStream.BA(encodedData.get(i)), chars, dest);
		return dest;
	}
	
	@Benchmark
	public Encoding findBest(){
		String testString = testStrings.get(random.nextInt(testStrings.size()));
		return Encoding.findBest(testString);
	}
}
