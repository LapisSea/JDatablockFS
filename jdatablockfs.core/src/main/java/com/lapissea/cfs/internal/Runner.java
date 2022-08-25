package com.lapissea.cfs.internal;

import java.util.concurrent.atomic.AtomicLong;

public class Runner{
	
	private static final AtomicLong TASK_NUM=new AtomicLong();
	public static void compileTask(Runnable task){
		Thread.ofVirtual()
		      .name("comp", TASK_NUM.incrementAndGet())
		      .start(task);
	}
	
}
