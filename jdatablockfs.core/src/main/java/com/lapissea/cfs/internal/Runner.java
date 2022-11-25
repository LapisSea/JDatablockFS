package com.lapissea.cfs.internal;

import java.util.concurrent.atomic.AtomicLong;

public class Runner{
	
	private static final AtomicLong TASK_NUM=new AtomicLong();
	public static void compileTask(Runnable task){
		var index=TASK_NUM.incrementAndGet();
		Thread.ofVirtual()
		      .name("comp", index)
		      .start(task);
	}
	
}
