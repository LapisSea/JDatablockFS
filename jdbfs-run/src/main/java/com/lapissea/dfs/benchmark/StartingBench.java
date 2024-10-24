package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.run.Sampling;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class StartingBench{
	
	static Sampling.Exec exec;
	
	@Setup(Level.Trial)
	public void start(){
//		exec = Sampling.sampleThread("samples.json", false, null);
//		exec.waitStarted();
	}
	@TearDown(Level.Trial)
	public void end(){
//		exec.waitEnded();
	}
	
	@Benchmark
	@Fork(value = 200, warmups = 1)
	@Warmup(iterations = 0)
	@Measurement(iterations = 1)
	@BenchmarkMode(Mode.SingleShotTime)
	public void initAndRoot(){
		try{
			var roots = Cluster.emptyMem().roots();
			roots.request("benchy", Reference.class);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
//		exec.end();
	}
	
}
