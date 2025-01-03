package com.lapissea.dfs.internal;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.logging.Log;
import com.lapissea.util.LateInit;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeSupplier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.lapissea.dfs.config.GlobalConfig.RELEASE_MODE;

public final class Runner{
	
	private static final class Task implements Runnable{
		
		private static final AtomicLong ID_COUNTER = new AtomicLong();
		
		private final    long     id    = ID_COUNTER.getAndIncrement();
		private final    Runnable task;
		private volatile boolean  started;
		private final    long     start = System.nanoTime();
		private          int      counter;
		
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
	
	private static final ArrayList<Task> TASKS = new ArrayList<>();
	
	private static final String  BASE_NAME    = ConfigDefs.RUNNER_BASE_TASK_NAME.resolveLocking();
	private static final boolean ONLY_VIRTUAL = ConfigDefs.RUNNER_ONLY_VIRTUAL_WORKERS.resolveValLocking();
	
	private static volatile int             virtualChoke = 0;
	private static          boolean         chokeWarningEmited;
	private static          ExecutorService platformExecutor;
	private static volatile Instant         lastTask;
	private static volatile Thread          watcher;
	
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
			
			long timeThreshold  = ConfigDefs.RUNNER_TASK_CHOKE_TIME.resolve().toNanos();
			int  watcherTimeout = (int)ConfigDefs.RUNNER_WATCHER_TIMEOUT.resolve().toMillis();
			var  toRestart      = new ArrayList<Task>();
			
			// debug wait prevents debugging sessions from often restarting the watcher by
			// repeatedly sleeping for a short time and checking if it should still exit.
			// When debugging, the short sleeps will extend when ever the code is paused
			var debugWait    = RELEASE_MODE? 0 : 200;
			var debugCounter = 0;
			
			while(true){
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
							TASKS.wait(chokeWarningEmited? 20 : 200);
						}catch(InterruptedException e){
							throw new RuntimeException(e);
						}
						continue;
					}
					lastTask = Instant.now();
					debugCounter = 0;
					
					var now     = System.nanoTime();
					var oldSize = TASKS.size();
					TASKS.removeIf(t -> {
						if(now - t.start<=timeThreshold) return false;
						if(t.started) return true;
						if(t.counter<20){
							t.counter++;
							return false;
						}
						toRestart.add(t);
						return true;
					});
					if(oldSize>16){
						var newSize = TASKS.size();
						if(oldSize/2>newSize){
							TASKS.trimToSize();
						}
					}
				}
				if(toRestart.isEmpty()){
					UtilL.sleep(1);
					continue;
				}
				
				emitChokeWarning();
				
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
				
				var cl = toRestart.size()>32;
				toRestart.clear();
				if(cl) toRestart.trimToSize();
			}
		});
	}
	
	private static void emitChokeWarning(){
		if(chokeWarningEmited) return;
		chokeWarningEmited = true;
		if(!ConfigDefs.RUNNER_MUTE_CHOKE_WARNING.resolveVal()){
			Log.warn("Virtual threads choking! Starting platform thread fallback to prevent possible deadlocks.\n" +
			         "\"{}\" property may be used to configure choke time threshold. (Set \"{}\" to true to mute this)",
			         ConfigDefs.RUNNER_TASK_CHOKE_TIME.name(), ConfigDefs.RUNNER_MUTE_CHOKE_WARNING.name());
		}
	}
	
	private static void virtualRun(Runnable task, String name){
		CompletableFuture.runAsync(task, Thread.ofVirtual().name(makeName(name, Task.ID_COUNTER.incrementAndGet()))::start);
	}
	
	private static void robustRun(Runnable task, String name){
		var t = new Task(task);
		
		CompletableFuture.runAsync(t, Thread.ofVirtual().name(makeName(name, t.id))::start);
		
		// If tasks were choking recently, it is beneficial to
		// immediately attempt to work on a platform thread
		if(virtualChoke>0){
			synchronized(Runner.class){
				if(virtualChoke>0) virtualChoke--;
			}
			CompletableFuture.runAsync(t, getPlatformExecutor());
		}else{
			synchronized(TASKS){
				pingWatcher();
				if(!TASKS.isEmpty() && TASKS.getFirst().started){
					TASKS.set(0, t);
				}else{
					TASKS.add(t);
					TASKS.notifyAll();
				}
			}
		}
	}
	private static String makeName(String name, long id){
		if(name == null) return BASE_NAME + id;
		return id + ":" + name;
	}
	
	/**
	 * <p>
	 * Executes a task in a timely manner. By default, it will run the task on a new virtual thread but in odd cases where
	 * congestion is high or all virtual threads are pinned due to synchronization, the task will be dispatched to a platform
	 * worker pool.
	 * </p>
	 * <p>
	 * If the thread has been waiting to start for more than {@link ConfigDefs#RUNNER_TASK_CHOKE_TIME}, it may be executed on a
	 * platform thread. <br>
	 * A task might be ran on a platform thread right away if a task start has timed out recently.
	 * </p>
	 *
	 * @param task A task to be executed
	 */
	public static void run(Runnable task, String taskName){
		if(ONLY_VIRTUAL){
			virtualRun(task, taskName);
		}else{
			robustRun(task, taskName);
		}
	}
	
	private static final Executor run = t -> Runner.run(t, null);
	
	/**
	 * Creates a {@link LateInit} with no checked exception that is executed as a task. See {@link Runner#run)}<br>
	 * This is useful when an expensive value can be computed asynchronously and fetched when ever it is ready and needed.
	 *
	 * @param task the constructor/generator of some data
	 * @return a new {@link LateInit} with no checked exception.
	 */
	public static <T> CompletableFuture<T> async(Supplier<T> task){
		return CompletableFuture.supplyAsync(task, run);
	}
	
	/**
	 * Creates a {@link LateInit} with an exception that is executed as a task. See {@link Runner#run)}<br>
	 * This is useful when an expensive value can be computed asynchronously and fetched when ever it is ready and needed.
	 *
	 * @param task the constructor/generator of some data that may throw a checked exception
	 * @return a new {@link LateInit} that may throw a checked exception
	 */
	public static <T, E extends Throwable> LateInit<T, E> asyncChecked(UnsafeSupplier<T, E> task){
		return new LateInit<>(task, run);
	}
}
