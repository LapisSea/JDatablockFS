package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.run.Configuration;
import com.lapissea.cfs.run.sparseimage.SparseImage;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.field.IOField;
import org.openjdk.jmh.annotations.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Warmup(iterations=6, time=400, timeUnit=TimeUnit.MILLISECONDS)
@Measurement(iterations=6, time=500, timeUnit=TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IOWalkBench{
	
	public static void main(String[] args){
		new IOWalkBench().doWalk();
	}
	
	private final Cluster                    cluster;
	private       MemoryWalker.PointerRecord rec=new MemoryWalker.PointerRecord(){
		@Override
		public <T extends IOInstance<T>> MemoryWalker.IterationOptions log(StructPipe<T> pipe, Reference instanceReference, IOField.Ref<T, ?> field, T instance, Reference value) throws IOException{
			return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
		}
		@Override
		public <T extends IOInstance<T>> MemoryWalker.IterationOptions logChunkPointer(StructPipe<T> pipe, Reference instanceReference, IOField<T, ChunkPointer> field, T instance, ChunkPointer value) throws IOException{
			return MemoryWalker.IterationOptions.CONTINUE_NO_SAVE;
		}
	};
	
	public IOWalkBench(){
		try{
			IOInterface mem=MemoryData.build().build();
			Cluster.init(mem);
			var c   =new Cluster(mem);
			var conf=new Configuration();
			
			Map<String, Object> vals=new HashMap<>();
			if(System.getProperty("radius")!=null){
				vals.put("radius", System.getProperty("radius"));
			}
			
			conf.load(()->vals.entrySet().stream());
			SparseImage.run(c, conf.getView());
			
			if("true".equals(System.getProperty("read_only_benchy"))){
				mem=mem.asReadOnly();
			}
			
			cluster=new Cluster(mem);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	
	public void doWalk(){
		try{
			var r=cluster.rootWalker();
			r.walk(rec);
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	public void walk(){
		doWalk();
	}
	@Benchmark
	@Fork(jvmArgsAppend="-Dradius=2")
	public void walk2(){
		doWalk();
	}
	@Benchmark
	@Fork(jvmArgsAppend="-Dradius=30")
	public void walk30(){
		doWalk();
	}
	
	@Benchmark
	@Fork(jvmArgsAppend="-Dread_only_benchy=true")
	public void walkReadOnly(){
		doWalk();
	}
	
}
