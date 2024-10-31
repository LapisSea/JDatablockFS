package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.Iters;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 6, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 20, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(15)
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
	
	public enum DataType{
		RANDOM, STABLE
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
		
		final Encoding[]                values;
		final EnumMap<Encoding, String> stable;
		
		EncodingVal(Encoding... values){
			this.values = values;
			var stableRandom = new RawRandom(Arrays.hashCode(values));
			stable = new EnumMap<Encoding, String>(Iters.from(values).toMap(e -> e, enc -> genStr(stableRandom, enc)));
		}
	}
	
	record CodingInfo(Encoding encoding, String text, int byteSize, byte[] encoded){ }
	
	private List<CodingInfo> infos;
	
	private final RandomGenerator random = new RawRandom();
	
	@Param("STABLE")
	private DataType dataType;
	
	@Param
	private EncodingVal encoding;
	
	private boolean doShuffle = true;
	private Thread  shufler;
	
	@Setup
	public void start(){
		if(dataType == DataType.RANDOM){
			LogUtil.println("RANDOMIZING DATA");
			shufler = Thread.ofPlatform().name("shufler").daemon(true).start(() -> {
				try{
					while(doShuffle){
						Thread.sleep(10);
						if(doShuffle) generateInfos();
					}
				}catch(InterruptedException ignored){ }finally{
					LogUtil.println("NOT RANDOMIZING DATA");
				}
			});
		}
	}
	
	@TearDown
	public void end(){
		doShuffle = false;
		if(shufler != null) shufler.interrupt();
		shufler = null;
	}
	
	@Setup(Level.Iteration)
	public void setup(){
		generateInfos();
	}
	
	private void generateInfos(){
		infos = Iters.of(encoding.values).toList(coding -> {
			var text = switch(dataType){
				case RANDOM -> genStr(random, coding);
				case STABLE -> encoding.stable.get(coding);
			};
			
			var dest = new ContentOutputBuilder();
			try{
				coding.write(dest, text);
			}catch(IOException ex){ throw new UncheckedIOException(ex); }
			
			return new CodingInfo(coding, text, coding.calcSize(text), dest.toByteArray());
		});
	}
	
	private static String genStr(RandomGenerator random, Encoding coding){
		return coding.format.randomString(random, 50, 50);
	}
	
	private CodingInfo randomInfo(){
		var infos = this.infos;
		return infos.get(random.nextInt(infos.size()));
	}
	
	@Benchmark
	public byte[] write() throws IOException{
		var info = randomInfo();
		
		var dest = new ContentOutputBuilder(info.byteSize);
		info.encoding.write(dest, info.text);
		return dest.toByteArray();
	}
	
	@Benchmark
	public String read() throws IOException{
		var info = randomInfo();
		
		var buff = CharBuffer.allocate(info.text.length());
		info.encoding.read(new ContentInputStream.BA(info.encoded), buff);
		return buff.flip().toString();

//		var chars = info.text.length();
//		var dest  = new StringBuilder(chars);
//		info.encoding.read(new ContentInputStream.BA(info.encoded), chars, dest);
//		return dest.toString();
	}
	
	@Benchmark
	public Encoding findBest(){
		var info = randomInfo();
		return Encoding.findBest(info.text);
	}
}
