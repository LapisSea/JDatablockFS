package bench;

import com.lapissea.dfs.inspect.display.primitives.IndexBuilder;
import com.lapissea.dfs.inspect.display.vk.enums.VkIndexType;
import org.lwjgl.BufferUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 20, time = 400, timeUnit = TimeUnit.MILLISECONDS)
@Fork(5)
public class IndexBuilderBenchmark{
	
	public static void main(String[] args) throws RunnerException{
		var options = new OptionsBuilder().include(IndexBuilderBenchmark.class.getSimpleName());
//		options.forks(0);
		new Runner(options.build()).run();
	}
	
	@State(Scope.Thread)
	public static class BenchmarkParams{
		
		@Param({
			"UINT8",
			"UINT16",
			"UINT32"
		})
		public VkIndexType indexType;
		
		@Param({"true", "false"})
		public boolean preSized;
		
		public final ByteBuffer dest = BufferUtils.createByteBuffer(8000);
	}
	
	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	public IndexBuilder indexAdding(BenchmarkParams params){
		var type = params.indexType;
		var max  = type.getMaxSize();
		
		var index = new IndexBuilder(params.preSized? 1000 : 8, type);
		
		for(int i = 0; i<1000; i++){
			var idx = i%max;
			index.addOffset(idx, 0);
		}
		params.dest.clear();
		index.transferTo(params.dest, type, 0);
		return index;
	}
}
