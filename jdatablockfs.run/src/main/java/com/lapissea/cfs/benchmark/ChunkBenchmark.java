package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.MagicID;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 6, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 6, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ChunkBenchmark{
	
	private final Chunk chunk;
	
	public ChunkBenchmark(){
		try{
			chunk = new Chunk(
				DataProvider.newVerySimpleProvider(), ChunkPointer.of(MagicID.size()),
				NumberSize.SHORT, 1000, 1000,
				NumberSize.SHORT, ChunkPointer.of(1000)
			);
			chunk.writeHeader();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	
	@Benchmark
	public void read(){
		try{
			chunk.readHeader();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	public void write(){
		try{
			chunk.writeHeader();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.abBenchmark.chunkOptimizedPipe=true")
	public void readGenerated(){
		try{
			chunk.readHeader();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.abBenchmark.chunkOptimizedPipe=true")
	public void writeGenerated(){
		try{
			chunk.writeHeader();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
}
