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
	
	private static ExecutorService PLATFORM_EXECUTOR;
	
	public static final  String  BASE_NAME        ="Task";
	private static final String  MUTE_CHOKE_NAME  ="runner.muteWarning";
	private static final String  MS_THRESHOLD_NAME="runner.chokeTime";
	private static final boolean ONLY_VIRTUAL     =GlobalConfig.configFlag("runner.onlyVirtual", false);
	
	private static ExecutorService getPlatformExecutor(){
		if(PLATFORM_EXECUTOR!=null) return PLATFORM_EXECUTOR;
		
		synchronized(Runner.class){
			if(PLATFORM_EXECUTOR!=null) return PLATFORM_EXECUTOR;
			
			PLATFORM_EXECUTOR=new ThreadPoolExecutor(
					0, Integer.MAX_VALUE,
					500, TimeUnit.MILLISECONDS,
					new SynchronousQueue<>(),
					Thread.ofPlatform()
					      .group(new ThreadGroup(TextUtil.plural(BASE_NAME)))
					      .priority(Thread.MAX_PRIORITY)//High priority. The faster the threads die the better.
					      .name(BASE_NAME+"PWorker", 0)
					      .daemon(true)::unstarted
			);
		}
		return PLATFORM_EXECUTOR;
	}
	
	static{
		Thread.ofPlatform().name(BASE_NAME+"-watcher").daemon(true).start(()->{
			if(ONLY_VIRTUAL) return;
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
		if(ONLY_VIRTUAL){
			virtualRun(task);
		}else{
			robustRun(task);
		}
	}
	
	private static void virtualRun(Runnable task){
		Thread.ofVirtual().name(BASE_NAME, Task.ID_COUNTER.incrementAndGet()).start(task);
	}
	
	private static void robustRun(Runnable task){
		var t=new Task(task);
		
		Thread.ofVirtual().name(BASE_NAME, t.id).start(t);
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
