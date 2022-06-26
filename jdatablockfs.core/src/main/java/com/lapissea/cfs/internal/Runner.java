package com.lapissea.cfs.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class Runner{
	
	private static final ExecutorService COMPILE_EXECUTOR;
	
	static{
		COMPILE_EXECUTOR=Executors.newCachedThreadPool(new ThreadFactory(){
			private long index=0;
			@Override
			public Thread newThread(Runnable r){
				var t=new Thread(r, "comp"+(index++));
				t.setDaemon(true);
				return t;
			}
		});
		COMPILE_EXECUTOR.execute(()->{});//Early warmup, better start time
	}
	
	public static void compileTask(Runnable task){
		COMPILE_EXECUTOR.execute(task);
	}
	
}
