package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.type.Struct;
import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;


@Warmup(iterations=6, time=2000, timeUnit=TimeUnit.MILLISECONDS)
@Measurement(iterations=3, time=2000, timeUnit=TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode({Mode.SingleShotTime, Mode.Throughput})
public class CompilationBench{
	
	@Benchmark
	@Fork(jvmArgsAppend="-Dcom.lapissea.cfs.internal.Access.NO_CACHE=true")
	public void hashmap(){
		var s=Struct.of(HashIOMap.class);
//		LogUtil.println(System.identityHashCode(s));
	}
	
}
