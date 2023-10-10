package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;


@Warmup(iterations = 5, time = 3000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 12, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MapWriteBench{
	
	@SuppressWarnings("unchecked")
	@Benchmark
	public void write() throws IOException{
		var cluster = Cluster.emptyMem();
		IOMap<Object, Object> map = cluster.roots()
		                                   .builder("map")
		                                   .withType(HashIOMap.class, Object.class, Object.class)
		                                   .request();
		
		for(int i = 0; i<100; i++){
			map.put(i, i + "");
		}
	}
}
