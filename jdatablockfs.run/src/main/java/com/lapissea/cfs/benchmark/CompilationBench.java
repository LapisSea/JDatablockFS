package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.type.Struct;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CompilationBench{
	
	@Benchmark
//	@Fork(jvmArgsAppend="-Dcom.lapissea.cfs.internal.Access.DEV_CACHE=true")
	@Warmup(iterations=6, time=2000, timeUnit=TimeUnit.MILLISECONDS)
	@Measurement(iterations=3, time=2000, timeUnit=TimeUnit.MILLISECONDS)
	@BenchmarkMode(Mode.Throughput)
	public void hashmap(){
		Struct.clear();
		StructPipe.clear();
		var s=Struct.of(HashIOMap.class);
		s.waitForState(Struct.STATE_DONE);
	}
	
	@Benchmark
	@Fork(value=30)
	@Warmup(iterations=0, time=2000, timeUnit=TimeUnit.MILLISECONDS)
	@Measurement(iterations=1, time=2000, timeUnit=TimeUnit.MILLISECONDS)
	@BenchmarkMode(Mode.SingleShotTime)
	public void hashmapDryRun(){
		var s=Struct.of(HashIOMap.class);
		s.waitForState(Struct.STATE_DONE);
	}
	
}
