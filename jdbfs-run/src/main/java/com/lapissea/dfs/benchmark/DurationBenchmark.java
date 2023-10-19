package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.chunk.DataProvider;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;


@Warmup(iterations = 5, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DurationBenchmark{

//	public static void main(String[] args) throws IOException{
//		var b = new DurationBenchmark();
//		b.init();
//		for(int i = 0; i<1000000000; i++){
//			if(i%10000000 == 0) LogUtil.println(i/1000000000D);
//			b.read();
//		}
//	}
	
	interface Hold extends IOInstance.Def<Hold>{
		Duration val();
	}
	
	static final StandardStructPipe<Hold> PIPE       = StandardStructPipe.of(Hold.class);
	static final Function<Duration, Hold> MAKE       = IOInstance.Def.constrRef(Hold.class, Duration.class);
	static final FixedStructPipe<Hold>    FIXED_PIPE = FixedStructPipe.of(Hold.class);
	
	private       Hold   val;
	private final byte[] bb = new byte[(int)PIPE.getSizeDescriptor().getMax(WordSpace.BYTE).orElseThrow()];
	
	@Setup(Level.Invocation)
	public void init() throws IOException{
		val = MAKE.apply(Duration.ofMillis(new Random().nextLong()));
		PIPE.write((DataProvider)null, new ContentOutputStream.BA(bb), val);
	}
	
	@Benchmark
	public void write() throws IOException{
		PIPE.write((DataProvider)null, new ContentOutputStream.BA(bb), val);
	}
	
	@Benchmark
	public void read() throws IOException{
		PIPE.readNew(null, new ContentInputStream.BA(bb), null);
	}
	
	@Benchmark
	public void writeFixed() throws IOException{
		FIXED_PIPE.write((DataProvider)null, new ContentOutputStream.BA(bb), val);
	}
	
	@Benchmark
	public void readFixed() throws IOException{
		FIXED_PIPE.readNew(null, new ContentInputStream.BA(bb), null);
	}
}
