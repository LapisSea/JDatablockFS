package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.iterableplus.Iters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
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
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 6, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ChunkBenchmark{
	
	public static void main(String[] args) throws Exception{
		var opt  = new OptionsBuilder().include(ChunkBenchmark.class.getSimpleName());
		var mode = args.length>=1? args[0] : "";
		if(mode.equals("json")){
			opt.resultFormat(ResultFormatType.JSON);
			var date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH-mm_dd-MM-yyyy"));
			opt.result("benchmarks/" + ChunkBenchmark.class.getSimpleName() + " " + date + ".json");
		}
		new Runner(opt.build()).run();
	}
	
	private Chunk[] chunks;
	
	@Setup(Level.Iteration)
	public void init(){
		chunks = Iters.from(NumberSize.class).filter(e -> e.maxSize>=1000).map(s -> {
			try{
				var chunk = new Chunk(
					DataProvider.newVerySimpleProvider(), ChunkPointer.of(MagicID.size()),
					s, 1000, 1000,
					s, ChunkPointer.of(1000)
				);
				chunk.writeHeader();
				return chunk;
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}).toArray(Chunk[]::new);
	}
	
	
	@Benchmark
	public void read() throws Exception{
		for(Chunk chunk : chunks){
			chunk.readHeader();
		}
	}
	
	@Benchmark
	public void write() throws Exception{
		for(Chunk chunk : chunks){
			chunk.writeHeader();
		}
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.optimizedPipe=false")
	public void readGeneric() throws Exception{
		for(Chunk chunk : chunks){
			chunk.readHeader();
		}
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.optimizedPipe=false")
	public void writeGeneric() throws Exception{
		for(Chunk chunk : chunks){
			chunk.writeHeader();
		}
	}
	
}
