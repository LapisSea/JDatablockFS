package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 4, time = 1000, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 15, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class MemoryManagementBenchmark{
	
	private byte[] src;
	
	private static byte[] generate(UnsafeConsumer<Cluster, IOException> action) throws IOException{
		var data = MemoryData.builder().build();
		var c    = Cluster.init(data);
		action.accept(c);
		return data.readAll();
	}
	
	@Param({"0", "50", "15000"})
	public int entropy;
	
	@Param({"1", "50", "200"})
	public int allocations;
	
	@Setup
	public void initSrc(){
		try{
			src = generate(c -> {
				Random      r   = new Random(69);
				List<Chunk> chs = new ArrayList<>();
				for(int i = 0; i<entropy; i++){
					if(r.nextFloat()>0.5 || chs.size()<2){
						chs.add(AllocateTicket.bytes(8 + r.nextInt(64)).submit(c));
					}else{
						var ch = chs.remove(r.nextInt(chs.size() - 1));
						if(ch.checkLastPhysical()){
							chs.add(ch);
							continue;
						}
						ch.freeChaining();
					}
				}
				
				LogUtil.println(entropy, c.getMemoryManager().getFreeChunks());
			});
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	private IOInterface mem;
	private Cluster     cls;
	
	@Setup(Level.Invocation)
	public void initData(){
		mem = MemoryData.builder().withRaw(src).build();
		try{
			cls = new Cluster(mem);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	
	//	@Benchmark
	public void initCluster(Blackhole hole){
		try{
			var c = new Cluster(mem);
			hole.consume(c);
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	public void alloc(){
		try{
			alloc(cls, AllocateTicket.bytes(16), allocations);
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	private void alloc(Cluster c, AllocateTicket ticket, int count) throws IOException{
		var man = c.getMemoryManager();
		for(int i = 0; i<count; i++){
			ticket.submit(man);
		}
	}
	
}
