package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.objects.Reference;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class StartingBench{
	
	@Benchmark
	@Fork(value=200, warmups=1)
	@Warmup(iterations=0)
	@Measurement(iterations=1)
	@BenchmarkMode(Mode.SingleShotTime)
	public void initAndRoot(){
		try{
			var roots=Cluster.emptyMem().getRootProvider();
			roots.request("benchy", Reference.class);
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
}
