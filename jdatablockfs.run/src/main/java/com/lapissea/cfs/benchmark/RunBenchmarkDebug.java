package com.lapissea.cfs.benchmark;

import com.lapissea.util.LogUtil;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

@SuppressWarnings("Convert2MethodRef")
public class RunBenchmarkDebug{
	
	public static void main(String[] args){
//		doWalk(args);
		
		var bench = new MemoryManagementBenchmark();
		bench.entropy = 15000;
		bench.allocations = 200;
		bench.initSrc();
		
		jitHandle(new String[]{"jit"}, bench, ioWalkBench -> {
			bench.initData();
			bench.alloc();
		});
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
