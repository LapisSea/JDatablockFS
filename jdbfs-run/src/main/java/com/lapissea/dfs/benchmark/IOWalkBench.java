package com.lapissea.dfs.benchmark;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.run.Configuration;
import com.lapissea.dfs.run.JMHRun;
import com.lapissea.dfs.run.sparseimage.SparseImage;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.fields.RefField;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 16, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 30, time = 2000, timeUnit = TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class IOWalkBench{
	public static void main(String[] args){ JMHRun.run(args); }
	
	private final Cluster                    cluster;
	final         MemoryWalker.PointerRecord rec = new MemoryWalker.PointerRecord(){
		@Override
		public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, RefField<T, ?> field, Reference valueReference){
			return MemoryWalker.CONTINUE;
		}
		@Override
		public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value){
			return MemoryWalker.CONTINUE;
		}
	};
	
	public IOWalkBench(){
		try{
			IOInterface mem  = MemoryData.empty();
			var         c    = Cluster.init(mem);
			var         conf = new Configuration();
			
			Map<String, Object> vals = new HashMap<>();
			if(System.getProperty("radius") != null){
				vals.put("radius", System.getProperty("radius"));
			}
			
			conf.load(() -> vals.entrySet().stream());
			SparseImage.run(c, conf.getView());
			
			if("true".equals(System.getProperty("read_only_benchy"))){
				mem = mem.asReadOnly();
			}
			
			cluster = new Cluster(mem);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	
	public void doWalk(){
		try{
			var r = cluster.rootWalker(rec, false);
			r.walk();
		}catch(Exception e){
			throw new RuntimeException(e);
		}
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.tweaks.fieldAccess=VAR_HANDLE")
	public void walkAccVarHandle(){
		doWalk();
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.tweaks.fieldAccess=REFLECTION")
	public void walkAccReflection(){
		doWalk();
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Ddfs.tweaks.fieldAccess=UNSAFE")
	public void walkAccUnsafe(){
		doWalk();
	}
	
	@Benchmark
	public void walk(){
		doWalk();
	}
	@Benchmark
	@Fork(jvmArgsAppend = "-Dradius=2")
	public void walk2(){
		doWalk();
	}
	@Benchmark
	@Fork(jvmArgsAppend = "-Dradius=80")
	public void walk30(){
		doWalk();
	}
	
	@Benchmark
	@Fork(jvmArgsAppend = "-Dread_only_benchy=true")
	public void walkReadOnly(){
		doWalk();
	}
	
}
