package com.lapissea.dfs.benchmark;

import com.lapissea.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

@SuppressWarnings("Convert2MethodRef")
public class RunBenchmarkDebug{
	
	public static void main(String[] args) throws IOException{
//		doWalk(args);
		
		var bench = new MemoryManagementBenchmark();
		var state = new MemoryManagementBenchmark.Chunks();
		state.freeChunks = 4096;
		state.setup();
		
		jitHandle(new String[]{"jit"}, bench, ioWalkBench -> {
			try{
				bench.allocateChunks(state);
				state.reclaim();
			}catch(IOException e){ throw new UncheckedIOException(e); }
		});
		state.checkBoundedFile();
	}
	
	private static void doWalk(String[] args){
		jitHandle(args, new IOWalkBench(), ioWalkBench -> {
			ioWalkBench.doWalk();
		});
	}
	
	private static <T> void jitHandle(String[] args, T t, Consumer<T> run){
		if(args.length == 1 && args[0].equals("jit")){
			Instant start = Instant.now();
			Instant print = Instant.now();
			int     i     = 0;
			
			while(Duration.between(start, Instant.now()).toSeconds()<20){
				i++;
				if(i<50000){
					start = Instant.now();
				}
				if(Duration.between(print, Instant.now()).toMillis()>=1000){
					print = Instant.now();
					LogUtil.println("=====Iterating=====", Duration.between(start, Instant.now()).toSeconds());
				}
				run.accept(t);
			}
			LogUtil.println(new File("mylogfile.log").getAbsolutePath());
		}else{
			run.accept(t);
		}
	}
	
}
