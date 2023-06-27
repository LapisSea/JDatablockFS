package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.type.field.annotations.IOCompression;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CompressionBench{
	
	//	@Param
//	@Param({"RLE"})
	@Param({"RLE", "LZ4_FAST"})
	public IOCompression.Type type;
	
	private byte[] raw;
	private byte[] compressed;
	
	@Setup(Level.Invocation)
	public void initData(){
		Random r = new Random();
		raw = new byte[r.nextInt(1000)];
		for(int i = 0; i<raw.length; ){
			if(r.nextFloat()<0.2){
				for(int to = Math.min(i + r.nextInt(300) + 1, raw.length); i<to; i++){
					raw[i] = (byte)r.nextInt(256);
				}
			}else{
				var b = (byte)r.nextInt(256);
				for(int to = Math.min(i + r.nextInt(200) + 1, raw.length); i<to; i++){
					raw[i] = b;
				}
			}
		}
	}
	
	@Setup(Level.Invocation)
	@Group("decompress")
	public void initCompress(){
		initData();
		compressed = type.pack(raw);
	}
	
	@Benchmark
	@Fork(10)
	@Warmup(iterations = 4, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
	@Measurement(iterations = 10, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
	@BenchmarkMode(Mode.Throughput)
	@Group("compress")
	public void compress(Blackhole blackhole){
		var compressed = type.pack(raw);
		blackhole.consume(compressed);
	}
	
	@Benchmark
//	@Fork(10)
	@Warmup(iterations = 4, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
	@Measurement(iterations = 10, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
	@BenchmarkMode(Mode.Throughput)
	@Group("decompress")
	public void decompress(Blackhole blackhole){
		var decompressed = type.unpack(compressed);
		blackhole.consume(decompressed);
	}
}
