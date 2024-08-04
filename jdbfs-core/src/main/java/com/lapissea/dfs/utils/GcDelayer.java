package com.lapissea.dfs.utils;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GcDelayer{
	
	private record Holder(Object ref) implements Runnable{
		@Override
		public void run(){ }
	}
	
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
		1,
		r -> Thread.ofPlatform().name("RefHolder").daemon().unstarted(r)
	);
	
	public void delay(Object ref, Duration delay){
		scheduler.schedule(new Holder(ref), delay.toMillis(), TimeUnit.MILLISECONDS);
	}
}
