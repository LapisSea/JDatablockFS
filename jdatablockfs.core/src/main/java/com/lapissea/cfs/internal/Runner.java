package com.lapissea.cfs.internal;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.logging.Log;
import com.lapissea.util.LateInit;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class Runner{
	
	private static class Task implements Runnable{
		
		private static final AtomicLong ID_COUNTER = new AtomicLong();
		
		private final long     id    = ID_COUNTER.getAndIncrement();
		private final Runnable task;
		private       boolean  started;
		private final long     start = System.nanoTime();
		private       int      counter;
		
		private Task(Runnable task){
			this.task = task;
		}
		
		@Override
		public void run(){
			synchronized(this){
				if(started) return;
				started = true;
			}
			this.task.run();
		}
	}
	
	private static final List<Task> TASKS = new ArrayList<>();
	
	private static final String  BASE_NAME             = "Task";
	private static final String  MUTE_CHOKE_NAME       = "runner.muteWarning";
	private static final String  THRESHOLD_NAME_MILIS  = "runner.chokeTime";
	private static final String  WATCHER_TIMEOUT_MILIS = "runner.watcherTimeout";
	private static final boolean ONLY_VIRTUAL          = GlobalConfig.configFlag("runner.onlyVirtual", false);
	
	private static int             virtualChoke = 0;
	private static boolean         cherry       = true;
	private static ExecutorService platformExecutor;
	private static Instant         lastTask;
	private static Thread          watcher;
	
	private static ExecutorService getPlatformExecutor(){
		if(platformExecutor == null) makeExecutor();
		return platformExecutor;
	}
	
	private static synchronized void makeExecutor(){
		if(platformExecutor != null) return;
		platformExecutor = new ThreadPoolExecutor(
			0, Integer.MAX_VALUE,
			500, TimeUnit.MILLISECONDS,
			new SynchronousQueue<>(),
			Thread.ofPlatform()
			      .group(new ThreadGroup(TextUtil.plural(BASE_NAME)))
			      .name(BASE_NAME + "PWorker", 0)
			      .daemon(true)::unstarted
		);
	}
	
	private static void pingWatcher(){
		if(ONLY_VIRTUAL) return;
		lastTask = Instant.now();
		if(watcher == null){
			watcher = startWatcher();
		}
	}
	
	private static Thread startWatcher(){
		return Thread.ofPlatform().name(BASE_NAME + "-watcher").daemon(true).start(() -> {
			Log.trace("{#yellowStarting " + BASE_NAME + "-watcher#}");
			
			int timeThreshold  = GlobalConfig.configInt(THRESHOLD_NAME_MILIS, 100);
			int watcherTimeout = GlobalConfig.configInt(WATCHER_TIMEOUT_MILIS, 1000);
			var toRestart      = new ArrayList<Task>();
			
			// debug wait prevents debugging sessions from often restarting the watcher by
			// repeatedly sleeping for a short time and checking if it should still exit.
			// When debugging the short sleeps will extend when ever the code is paused
			var debugWait    = GlobalConfig.RELEASE_MODE? 0 : 200;
			var debugCounter = 0;
			
			while(true){
				boolean counted = false;
				synchronized(TASKS){
					if(TASKS.isEmpty()){
						var d = Duration.between(lastTask, Instant.now());
						if(d.toMillis() - debugWait>watcherTimeout){
							if(debugCounter<debugWait){
								UtilL.sleep(1);
								debugCounter++;
								continue;
							}
							watcher = null;
							Log.trace("{#yellowShut down " + BASE_NAME + "-watcher#}");
							return;
						}
						debugCounter = 0;
						try{
							TASKS.wait(cherry? 200 : 20);
						}catch(InterruptedException e){
							throw new RuntimeException(e);
						}
						continue;
					}
					lastTask = Instant.now();
					debugCounter = 0;
					
					for(int i = TASKS.size() - 1; i>=0; i--){
						var t = TASKS.get(i);
						if(t.started){
							TASKS.remove(i);
							continue;
						}
						if(System.nanoTime() - t.start>timeThreshold*1000_000L){
							if(t.counter<20){
								t.counter++;
								counted = true;
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
					getPlatformExecutor().execute(() -> {
						synchronized(task){
							if(task.started) return;
							task.started = true;
						}
						synchronized(Runner.class){
							if(virtualChoke<100) virtualChoke += 5;
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
		if(cherry){
			cherry = false;
			if(!GlobalConfig.configFlag(MUTE_CHOKE_NAME, false)){
				Log.warn("Virtual threads choking! Starting platform thread fallback to prevent possible deadlocks.\n" +
				         "\"{}\" property may be used to configure choke time threshold. (Set \"{}\" to true to mute this)",
				         GlobalConfig.propName(THRESHOLD_NAME_MILIS), GlobalConfig.propName(MUTE_CHOKE_NAME));
			}
		}
	}
	
	private static void virtualRun(Runnable task){
		Thread.ofVirtual().name(BASE_NAME, Task.ID_COUNTER.incrementAndGet()).start(task);
	}
	
	private static void robustRun(Runnable task){
		var t = new Task(task);
		
		Thread.ofVirtual().name(BASE_NAME, t.id).start(t);
		
		// If tasks were choking recently, it is beneficial to
		// immediately attempt to work on a platform thread
		if(virtualChoke>0){
			synchronized(Runner.class){
				if(virtualChoke>0) virtualChoke--;
			}
			getPlatformExecutor().execute(t);
		}else{
			synchronized(TASKS){
				pingWatcher();
				TASKS.add(t);
				TASKS.notifyAll();
			}
		}
	}
	
	/**
	 * <p>
	 * Executes a task in a timely manner. By default, it will run the task on a new virtual thread but in odd cases where
	 * congestion is high or all virtual threads are pinned due to synchronization, the task will be dispatched to a platform
	 * worker pool.
	 * </p>
	 * <p>
	 * If the thread has been waiting to start for more than {@link Runner#THRESHOLD_NAME_MILIS}, it may be executed on a
	 * platform thread. <br>
	 * A task might be ran on a platform thread right away if a task start has timed out recently.
	 * </p>
	 *
	 * @param task A task to be executed
	 */
	public static void run(Runnable task){
		if(ONLY_VIRTUAL){
			virtualRun(task);
		}else{
			robustRun(task);
		}
	}
	
	/**
	 * Creates a {@link LateInit} with no checked exception that is executed as a task. See {@link Runner#run)}<br>
	 * This is useful when an expensive value can be computed asynchronously and fetched when ever it is ready and needed.
	 *
	 * @param task the constructor/generator of some data
	 * @return a new {@link LateInit} with no checked exception.
	 */
	public static <T> LateInit.Safe<T> async(Supplier<T> task){
		return new LateInit.Safe<>(task, Runner::run);
	}
	
	/**
	 * Creates a {@link LateInit} with an exception that is executed as a task. See {@link Runner#run)}<br>
	 * This is useful when an expensive value can be computed asynchronously and fetched when ever it is ready and needed.
	 *
	 * @param task the constructor/generator of some data that may throw a checked exception
	 * @return a new {@link LateInit} that may throw a checked exception
	 */
	public static <T, E extends Throwable> LateInit<T, E> asyncChecked(UnsafeSupplier<T, E> task){
		return new LateInit<>(task, Runner::run);
	}
}
