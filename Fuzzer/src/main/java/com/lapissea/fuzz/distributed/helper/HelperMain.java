package com.lapissea.fuzz.distributed.helper;

import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.distributed.RecipeCodec;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;
import com.lapissea.fuzz.distributed.bootstrap.RemoteWorkspace;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class HelperMain{
	
	public static void main(String[] args) throws Exception{
		if(args.length<6){
			throw new IllegalArgumentException("HelperMain requires at least 6 arguments, got " + args.length);
		}
		try(
			var workspace = args.length>8? RemoteWorkspace.open(Path.of(args[6]), Path.of(args[7]), Path.of(args[8])) : null;
			var wire = new Wire(new Socket(args[0], Integer.parseInt(args[1])))
		){
			
			int requested = Integer.parseInt(args[4]);
			int workers   = requested == 0? Math.max(1, Runtime.getRuntime().availableProcessors()) : requested;
			
			wire.send(new Wire.Hello(Wire.VERSION, args[2], args[3], workers, args[5], Runtime.version().feature()));
			
			System.setOut(new PrintStream(new LogStream(wire, false), true, StandardCharsets.UTF_8));
			System.setErr(new PrintStream(new LogStream(wire, true), true, StandardCharsets.UTF_8));
			
			var pool     = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
			var starts   = new ConcurrentHashMap<String, CompletableFuture<Boolean>>();
			var attempts = new ConcurrentHashMap<String, WorkTask>();
			
			var cancelled   = new AtomicBoolean();
			var lastMessage = new AtomicLong(System.nanoTime());
			var finishing   = new AtomicLong();
			var demandJob   = new AtomicReference<DemandJob>();
			var activeJob   = new AtomicReference<UUID>();
			
			var demandReporter = !Boolean.getBoolean("fuzz.workerDiagnostics")? null : Thread.ofVirtual().name("fuzz-work-demand").start(() -> {
				try{
					while(!Thread.currentThread().isInterrupted()){
						Thread.sleep(250);
						var job = demandJob.get();
						if(job == null || job.cancelled().get() || !pool.getQueue().isEmpty()){
							continue;
						}
						int idle          = Math.max(0, workers - pool.getActiveCount());
						int awaitingStart = Math.min(workers, starts.size());
						wire.send(new Wire.Demand(job.id(), idle, awaitingStart));
					}
				}catch(InterruptedException ignored){
					Thread.currentThread().interrupt();
				}catch(IOException e){
					quietClose(wire);
				}
			});
			
			int silenceTimeoutSeconds = Integer.getInteger("fuzz.silenceTimeoutSeconds", Wire.SILENCE_TIMEOUT_SECONDS);
			
			Thread.ofVirtual().name("fuzz-master-watchdog").start(() -> {
				while(true){
					try{
						Thread.sleep(100);
					}catch(InterruptedException e){
						return;
					}
					long now = System.nanoTime(), done = finishing.get();
					if((done != 0 && now - done>TimeUnit.SECONDS.toNanos(5)) ||
					   now - lastMessage.get()>TimeUnit.SECONDS.toNanos(silenceTimeoutSeconds)){
						// Kill unresponsive jobs as well as the protocol loop. Shutdown hooks release workspace
						System.exit(2);
					}
				}
			});
			
			FuzzingRunner<?, ?, ?> runner      = null;
			boolean                runPrepared = false;
			try{
				while(true){
					var frame = wire.receive();
					lastMessage.set(System.nanoTime());
					switch(frame){
						case Wire.Job(var binRecipe) -> {
							demandJob.set(null);
							
							if(runPrepared){
								cancelAll(cancelled, attempts, starts, pool);
								awaitIdle(pool, lastMessage);
								runPrepared = false;
								runner.cleanupRun();
							}
							
							try{
								var recipe = RecipeCodec.decode(binRecipe);
								runner = Objects.requireNonNull(recipe.create(), "Distributed recipe returned null");
								
								cancelled = new AtomicBoolean();
								runPrepared = true;
								runner.prepareRun();
							}catch(Exception e){
								runPrepared = false;
								cancelled.set(true);
								runner = null;
								// Report for the last known job so a pending master cleanup ack fails with a reason
								// instead of timing out, then let the fatal setup error end this helper.
								reportJobFailure(wire, activeJob.get(), e);
								throw e;
							}
						}
						case Wire.Work(var assignment, var sequence) -> {
							activeJob.set(assignment.job());
							
							var    current = runner;
							var    stop    = cancelled;
							String key     = assignment.key();
							
							var revoked = new AtomicBoolean();
							if(current == null){
								throw new IOException("Work before job");
							}
							
							var task = new WorkTask(revoked, () -> {
								try{
									if(stop.get() || revoked.get()){ return; }
									var permission = new CompletableFuture<Boolean>();
									starts.put(key, permission);
									if(stop.get() || revoked.get()){ permission.complete(false); }
									wire.send(new Wire.Start(assignment));
									if(!permission.get()){
										starts.remove(key);
										return;
									}
									starts.remove(key);
									var latest = new FuzzingRunner.SequenceProgress[]{new FuzzingRunner.SequenceProgress(0, 0)};
									var outcome = current.executeSequence(
										RunMark.NONE, sequence, () -> stop.get() || revoked.get() || Thread.currentThread().isInterrupted(),
										progress -> {
											latest[0] = progress;
											try{
												wire.send(new Wire.Progress(assignment, RemoteSequenceResult.ExecutionStats.of(progress)));
											}catch(IOException e){
												stop.set(true);
												revoked.set(true);
												quietClose(wire);
											}
										});
									wire.send(new Wire.Result(RemoteSequenceResult.of(assignment, sequence, outcome, RemoteSequenceResult.ExecutionStats.of(latest[0]))));
								}catch(Throwable e){
									try{
										wire.send(new Wire.Result(new RemoteSequenceResult(
											assignment, sequence,
											(stop.get() || revoked.get()?
											 new RemoteSequenceResult.Cancelled("Job cancelled") :
											 new RemoteSequenceResult.InfrastructureError(e.toString())),
											RemoteSequenceResult.CapturedOutput.EMPTY
										)));
									}catch(IOException ignored){ }
								}finally{
									starts.remove(key);
								}
							}){
								@Override
								protected void done(){
									attempts.remove(key, this);
								}
							};
							if(demandReporter != null){ demandJob.set(new DemandJob(assignment.job(), stop)); }
							attempts.put(key, task);
							pool.execute(task);
						}
						case Wire.Cancel(var jobId) -> {
							cancelAll(cancelled, attempts, starts, pool);
							
							awaitIdle(pool, lastMessage);
							
							Throwable cleanupFailure = null;
							if(runPrepared){
								runPrepared = false;
								try{
									runner.cleanupRun();
								}catch(Throwable e){
									cleanupFailure = e;
								}
							}
							runner = null;
							
							if(jobId != null){
								wire.send(new Wire.CleanupComplete(jobId, cleanupFailure == null? null : cleanupFailure.toString()));
							}
							
							if(cleanupFailure != null){
								throw new IllegalStateException("Remote run cleanup failed", cleanupFailure);
							}
						}
						case Wire.StartAck message -> {
							String  key     = message.assignment().key();
							boolean allowed = message.allowed();
							var     pending = starts.get(key);
							if(pending != null){
								pending.complete(allowed);
							}
						}
						case Wire.CancelAssignment message -> {
							String key     = message.assignment().key();
							var    attempt = attempts.get(key);
							if(attempt != null){
								attempt.revoke(pool);
							}
							var pending = starts.get(key);
							if(pending != null){
								pending.complete(false);
							}
						}
						case Wire.Ping ignored -> wire.send(new Wire.Ping());
						default -> throw new IOException("Unknown command");
					}
				}
			}finally{
				if(demandReporter != null) demandReporter.interrupt();
				cancelAll(cancelled, attempts, starts, pool);
				pool.shutdownNow();
				try{
					pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
				}finally{
					if(runPrepared){
						runPrepared = false;
						runner.cleanupRun();
					}
					finishing.set(System.nanoTime());
				}
			}
		}
	}
	
	private static void awaitIdle(ThreadPoolExecutor pool, AtomicLong lastMessage) throws InterruptedException{
		// Refresh the silence timer while waiting for tasks, but stop after a grace window so the watchdog
		// can reap a task that ignores interrupts instead of this loop spinning (and refreshing) forever.
		long refreshUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(Wire.SILENCE_TIMEOUT_SECONDS - 5L);
		while(pool.getActiveCount() != 0){
			if(System.nanoTime()<refreshUntil){ lastMessage.set(System.nanoTime()); }
			Thread.sleep(10);
		}
	}
	
	private static void quietClose(AutoCloseable resource){
		try{
			resource.close();
		}catch(Exception ignored){ }
	}
	
	private static void reportJobFailure(Wire wire, UUID jobId, Throwable failure){
		if(jobId == null){ return; }
		try{
			wire.send(new Wire.CleanupComplete(jobId, "Fuzzer helper failed to prepare a job: " + failure));
		}catch(IOException ignored){ }
	}
	
	private static void cancelAll(
		AtomicBoolean cancelled, Map<String, WorkTask> attempts,
		Map<String, CompletableFuture<Boolean>> starts, ThreadPoolExecutor pool
	){
		cancelled.set(true);
		attempts.values().forEach(task -> task.revoke(pool));
		starts.values().forEach(f -> f.complete(false));
		starts.clear();
	}
	
	private record DemandJob(UUID id, AtomicBoolean cancelled){ }
	
	static class WorkTask extends FutureTask<Void>{
		
		private final AtomicBoolean revoked;
		
		WorkTask(AtomicBoolean revoked, Runnable action){
			super(action, null);
			this.revoked = revoked;
		}
		void revoke(ThreadPoolExecutor pool){
			revoked.set(true);
			cancel(true);
			pool.remove(this);
		}
	}
	
	private static final class LogStream extends OutputStream{
		
		final Wire    wire;
		final boolean error;
		
		LogStream(Wire wire, boolean error){
			this.wire = wire;
			this.error = error;
		}
		
		@Override
		public void write(int b) throws IOException{ write(new byte[]{(byte)b}, 0, 1); }
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			for(int pos = off; pos<off + len; ){
				int count = Math.min(Wire.MAX_OUTPUT, off + len - pos), start = pos;
				wire.send(new Wire.Output(error, Arrays.copyOfRange(b, start, start + count)));
				pos += count;
			}
		}
	}
}
