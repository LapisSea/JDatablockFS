package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.Reference;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class StartingBench{
	
	@Benchmark
	@Fork(value=200)
	@Warmup(iterations=0)
	@Measurement(iterations=1)
	@BenchmarkMode(Mode.SingleShotTime)
	public void initAndRoot(){
		try{
			var mem  =MemoryData.builder().build();
			var roots=Cluster.init(mem).getRootProvider();
			roots.request(Reference.class, "benchy");
		}catch(Throwable e){
			throw new RuntimeException(e);
		}
	}
	
}
