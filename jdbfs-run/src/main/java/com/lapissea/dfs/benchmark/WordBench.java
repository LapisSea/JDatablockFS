package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.internal.WordIO;
import com.lapissea.dfs.utils.RawRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.concurrent.TimeUnit;


@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Fork(10)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class WordBench{
	
	private final byte[] bb;
	
	private final RawRandom rand = new RawRandom();
	
	public WordBench(){
		bb = rand.nextBytes(20);
	}
	
	@Param({"1", "2", "3", "4", "5", "6", "7", "8"})
	public int size;
	
	@Benchmark
	public void read(Blackhole hole) throws IOException{
		for(int i = 0; i<100; i++){
			var pos = rand.nextInt(20 - 8);
			hole.consume(WordIO.getWord(bb, pos, size));
		}
	}
	
	@Benchmark
	public void write() throws IOException{
		var v = rand.nextLong();
		for(int i = 0; i<100; i++){
			var pos = rand.nextInt(20 - 8);
			WordIO.setWord(v, bb, pos, size);
		}
	}
}
