package com.lapissea.cfs.internal;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.logging.Log;
import com.lapissea.util.UtilL;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class Runner{
	
	private static ExecutorService PLATFORM_EXECUTOR;
	private static ExecutorService getPlatformExecutor(){
		if(PLATFORM_EXECUTOR!=null) return PLATFORM_EXECUTOR;
		
		synchronized(Runner.class){
			if(PLATFORM_EXECUTOR!=null) return PLATFORM_EXECUTOR;
			var group=new ThreadGroup("Task run");
			var index=new AtomicLong();
			PLATFORM_EXECUTOR=new ThreadPoolExecutor(
				0, Integer.MAX_VALUE,
				500, TimeUnit.MILLISECONDS,
				new SynchronousQueue<>(),
				r->{
					var t=new Thread(group, r, "PlatformWorker-"+(index.incrementAndGet()));
					t.setDaemon(true);
					return t;
				}
			);
		}
		return PLATFORM_EXECUTOR;
	}
	
	private static class Task implements Runnable{
		
		private static final AtomicLong ID_COUNTER=new AtomicLong();
		
		private final long     id   =ID_COUNTER.getAndIncrement();
		private final Runnable task;
		private       boolean  started;
		private final long     start=System.nanoTime();
		private       int      counter;
		
		private Task(Runnable task){
			this.task=task;
		}
		
		@Override
		public void run(){
			synchronized(this){
				if(started) return;
				started=true;
			}
			this.task.run();
		}
	}
	
	private static final List<Task> TASKS=new ArrayList<>();
	
	private static int     VIRTUAL_CHOKE=0;
	private static boolean CHERRY       =true;
	
	private static final String MUTE_CHOKE_NAME  ="muteChokeWarning";
	private static final String MS_THRESHOLD_NAME="virtualThreadChokeMs";
	
	static{
		Thread.ofPlatform().name("Task watcher").daemon(true).start(()->{
			int timeThreshold=GlobalConfig.configInt(MS_THRESHOLD_NAME, 100);
			var toRestart    =new ArrayList<Task>();
			while(true){
				boolean counted=false;
				synchronized(TASKS){
					if(TASKS.isEmpty()){
						try{
							TASKS.wait(CHERRY?200:20);
						}catch(InterruptedException e){
							throw new RuntimeException(e);
						}
						continue;
					}
					for(int i=TASKS.size()-1;i>=0;i--){
						var t=TASKS.get(i);
						if(t.started){
							TASKS.remove(i);
							continue;
						}
						if(System.nanoTime()-t.start>timeThreshold*1000_000L){
							if(t.counter<20){
								t.counter++;
								counted=true;
								continue;
							}
							TASKS.remove(i);
							toRestart.add(t);
						}
					}
				}
				if(toRestart.isEmpty()){
					if(counted) UtilL.sleep(1);
					continue;
				}
				
				pop();
				
				for(Task task : toRestart){
					Log.debug("{#redPerformance:#} Virtual threads choking, running task {}#green on platform", task.id);
					getPlatformExecutor().execute(()->{
						synchronized(task){
							if(task.started) return;
							task.started=true;
						}
						synchronized(Runner.class){
							if(VIRTUAL_CHOKE<100) VIRTUAL_CHOKE+=5;
						}
						task.task.run();
						Log.trace("{#redPerformance:#} Choked task {}#green completed", task.id);
					});
				}
				
				toRestart.clear();
				toRestart.trimToSize();
			}
		});
	}
	
	private static void pop(){
		if(CHERRY){
			CHERRY=false;
			if(!GlobalConfig.configFlag(MUTE_CHOKE_NAME, false)){
				Log.warn("Virtual threads choking! Starting platform thread fallback to prevent possible deadlocks.\n"+
				         "\"{}\" property may be used to configure choke time threshold. (Set \"{}\" to true to mute this)",
				         GlobalConfig.propName(MUTE_CHOKE_NAME), GlobalConfig.propName(MUTE_CHOKE_NAME));
			}
		}
	}
	
	public static void run(Runnable task){
		var t=new Task(task);
		
		Thread.ofVirtual().name("comp", t.id).start(t);
		if(VIRTUAL_CHOKE>0){
			synchronized(Runner.class){
				if(VIRTUAL_CHOKE>0) VIRTUAL_CHOKE--;
			}
			getPlatformExecutor().execute(t);
		}else{
			synchronized(TASKS){
				TASKS.add(t);
				TASKS.notifyAll();
			}
		}
	}
	
	public static <T> CompletableFuture<T> async(Supplier<T> task){
		var c=new CompletableFuture<T>();
		run(()->{
			try{
				c.complete(task.get());
			}catch(Throwable e){
				c.completeExceptionally(e);
			}
		});
		return c;
	}
	
}
