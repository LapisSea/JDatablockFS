package com.lapissea.cfs.benchmark;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.MemoryWalker;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public class RunBenchmarkDebug{
	
	public static void main(String[] args){
//		doWalk(args);
		
		var bench=new MemoryManagementBenchmark();
		bench.initSrc();
		bench.initData();
		bench.alloc50();
	}
	
	private static void doWalk(String[] args){
		if(args.length==1&&args[0].equals("jit")){
			System.setProperty("radius", "80");
			var     b      =new IOWalkBench();
			Instant instant=Instant.now();
			int     i      =0;
			
			while(Duration.between(instant, Instant.now()).toSeconds()<20){
				i++;
				if(i<50000){
					instant=Instant.now();
				}else if(i%10==0){
					LogUtil.println("=====Iterating=====", Duration.between(instant, Instant.now()).toSeconds());
					b.rec=new MemoryWalker.PointerRecord(){
						@Override
						public <T extends IOInstance<T>> int log(Reference instanceReference, T instance, IOField.Ref<T, ?> field, Reference valueReference) throws IOException{
							return MemoryWalker.CONTINUE;
						}
						@Override
						public <T extends IOInstance<T>> int logChunkPointer(Reference instanceReference, T instance, IOField<T, ChunkPointer> field, ChunkPointer value) throws IOException{
							return MemoryWalker.CONTINUE;
						}
					};
				}
				b.walk30();
			}
			LogUtil.println(new File("mylogfile.log").getAbsolutePath());
			return;
		}
		new IOWalkBench().doWalk();
	}
	
}
