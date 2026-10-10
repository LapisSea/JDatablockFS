package com.lapissea.fuzz.internal;

import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzFail;
import com.lapissea.fuzz.FuzzProgress;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzSequenceSource;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingRunner.SequenceProgress;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.SequenceOutcome;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * The executor that coordinates work between one or multiple machines. It handles cancellation and keeps track of progress
 */
public final class RunCoordinator{
	private RunCoordinator(){ }
	
	public static final class Work{
		
		private final long            id;
		private final FuzzSequence    sequence;
		private final BooleanSupplier cancelled;
		
		private SequenceProgress last = new SequenceProgress(0, 0);
		
		public Work(long id, FuzzSequence sequence, BooleanSupplier cancelled){
			this.id = id;
			this.sequence = sequence;
			this.cancelled = cancelled;
		}
		
		public long id()              { return id; }
		public FuzzSequence sequence(){ return sequence; }
		public boolean cancelled()    { return cancelled.getAsBoolean(); }
	}
	
	public interface Workers<S, A> extends AutoCloseable{
		Work claimLocal();
		
		void progress(Work work, SequenceProgress sample);
		
		boolean completeLocal(Work work, SequenceOutcome<S, A> outcome, SequenceProgress sample);
		
		void tick() throws InterruptedException;
		
		boolean isComplete();
		void cancel();
		@Override
		void close();
	}
	
	public interface WorkerFactory<S, A>{
		/**
		 * @return null when helper discovery has settled without a usable helper
		 */
		Workers<S, A> create(Context<S, A> context, Iterator<FuzzSequence> source);
	}
	
	public static final class Context<S, A>{
		private final    FuzzConfig                config;
		private final    FuzzProgress              progress;
		private final    BlockingQueue<Runnable>   events   = new LinkedBlockingQueue<>();
		private final    ArrayList<FuzzFail<S, A>> failures = new ArrayList<>();
		private          long                      firstFailure;
		private          boolean                   hasFailure;
		private volatile boolean                   cancelled;
		private final    long                      errorDelay;
		
		private Context(FuzzConfig config, FuzzProgress progress){
			this.config = config;
			this.progress = progress;
			long delay;
			try{ delay = config.errorDelay().toNanos(); }catch(ArithmeticException e){ delay = Long.MAX_VALUE; }
			errorDelay = delay;
		}
		public FuzzProgress progress(){ return progress; }
		public void wake()            { events.offer(() -> { }); }
		/**
		 * Called on the coordinator thread, at acceptance rather than after a remote replay.
		 */
		public void failureObserved(){
			if(!hasFailure){
				hasFailure = true;
				firstFailure = System.nanoTime();
				progress.errLater();
			}
		}
		public void retainFailure(FuzzFail<S, A> failure){
			failureObserved();
			var cap = config.errorTracking().maxErrorsTracked();
			if(cap.isPresent() && failures.size()>=cap.getAsInt()){ FuzzFail.deduplicateFailures(failures, config); }
			if(cap.isEmpty() || failures.size()<cap.getAsInt()){ failures.add(failure); }
		}
		public boolean cancelled()      { return cancelled; }
		public boolean deadlineReached(){ return hasFailure && System.nanoTime() - firstFailure>=errorDelay; }
		
		private long waitNanos(){
			long interval = TimeUnit.MILLISECONDS.toNanos(50);
			return cancelled || !hasFailure? interval : Math.clamp(errorDelay - (System.nanoTime() - firstFailure), 0, interval);
		}
		
		private List<FuzzFail<S, A>> results(){
			FuzzFail.deduplicateFailures(failures, config);
			return FuzzFail.sortFails(failures, config.failOrder().orElse(null));
		}
	}
	
	private static final class LocalWorkers<S, A> implements Workers<S, A>{
		
		private final Iterator<FuzzSequence> source;
		private final FuzzProgress           progress;
		
		private          int     active;
		private          boolean exhausted;
		private volatile boolean cancelled;
		
		private LocalWorkers(Iterator<FuzzSequence> source, FuzzProgress progress){
			this.source = source;
			this.progress = progress;
		}
		
		@Override
		public Work claimLocal(){
			if(cancelled || exhausted){ return null; }
			if(!source.hasNext()){
				exhausted = true;
				return null;
			}
			active++;
			return new Work(0, source.next(), () -> cancelled);
		}
		
		@Override
		public void progress(Work work, SequenceProgress sample){
			progress.reportWorkerTiming(
				"local",
				sample.completedOperations() - work.last.completedOperations(),
				sample.executionNanos() - work.last.executionNanos()
			);
			progress.reportResolvedSteps(sample.completedOperations() - work.last.completedOperations());
		}
		
		@Override
		public boolean completeLocal(Work work, SequenceOutcome<S, A> outcome, SequenceProgress sample){
			active--;
			return true;
		}
		@Override
		public void tick(){ }
		@Override
		public boolean isComplete(){ return cancelled || exhausted && active == 0; }
		@Override
		public void cancel(){ cancelled = true; }
		@Override
		public void close(){ }
	}
	
	public static <S, A, E extends Throwable> List<FuzzFail<S, A>> run(
		FuzzingRunner<S, A, E> runner, FuzzConfig config, RunMark mark,
		FuzzSequenceSource sequences, WorkerFactory<S, A> remote
	){
		if(Thread.currentThread().isInterrupted()){
			throw new CancellationException("Master cancelled");
		}
		
		// Retain the bounded preflight: small runs know their total immediately, large/slow sources count asynchronously.
		var     total   = new CompletableFuture<Long>();
		long    count   = 0, iterations = 0, started = System.nanoTime();
		boolean counted = true;
		
		try(var scan = sequences.all()){
			var iterator = scan.iterator();
			int checked  = 0;
			
			while(iterator.hasNext()){
				if(++checked%5 == 0 && System.nanoTime() - started>100_000_000L){
					counted = false;
					break;
				}
				
				var sequence = iterator.next();
				if(runner.shouldRunSequence(sequence, mark)){
					count++;
					iterations = Math.addExact(iterations, sequence.iterations());
				}
			}
		}
		
		if(counted) total.complete(iterations);
		if(counted && count == 0){
			return List.of();
		}
		
		var    progress = new FuzzProgress(config, total);
		var    context  = new Context<S, A>(config, progress);
		Thread counter  = null;
		if(!counted && config.shouldLog()){
			counter = Thread.ofVirtual().name("fuzz-progress-total").start(() -> {
				try(var scan = sequences.all()){
					long sum      = 0;
					var  iterator = scan.iterator();
					while(!Thread.currentThread().isInterrupted() && iterator.hasNext()){
						var sequence = iterator.next();
						if(runner.shouldRunSequence(sequence, mark)){
							sum = Math.addExact(sum, sequence.iterations());
						}
					}
					if(!Thread.currentThread().isInterrupted()){
						total.complete(sum);
					}
				}catch(Throwable e){
					total.completeExceptionally(e);
				}
			});
		}
		
		int             threads  = counted? (int)Math.min(count, config.maxWorkers()) : config.maxWorkers();
		ExecutorService pool     = null;
		Workers<S, A>   workers  = null;
		boolean         complete = false;
		List<FuzzFail<S, A>> results = null;
		
		try(Stream<FuzzSequence> source = sequences.all()){
			Iterator<FuzzSequence> iterator = source.filter(sequence -> runner.shouldRunSequence(sequence, mark)).iterator();
			workers = remote == null? null : remote.create(context, iterator);
			
			if(workers == null){
				workers = new LocalWorkers<>(iterator, progress);
			}
			
			var activeWorkers = workers;
			progress.reportStart();
			
			// A local single sequence needs no executor
			if(counted && count == 1 && workers instanceof LocalWorkers){
				var work = workers.claimLocal();
				if(work != null){
					var outcome = execute(runner, mark, context, activeWorkers, work);
					if(Thread.currentThread().isInterrupted()){ throw new CancellationException("Master cancelled"); }
					workers.completeLocal(work, outcome, work.last);
					if(outcome instanceof SequenceOutcome.Failed<S, A> failure){ context.retainFailure(failure.failure()); }
				}
				complete = true;
				results = context.results();
			}else{
			
			pool = Executors.newFixedThreadPool(threads, Thread.ofPlatform()
			                                                   .name("fuzzWorker(" + config.name().orElse("unnamed") + ")-", 0)
			                                                   .factory());
			int[] active = {0};
			while(true){
				if(Thread.currentThread().isInterrupted()){ throw new CancellationException("Master cancelled"); }
				workers.tick();
				
				if(!context.cancelled && context.deadlineReached()){
					context.cancelled = true;
					progress.err();
					workers.cancel();
					pool.shutdownNow();
				}
				
				while(!context.cancelled && active[0]<threads){
					var work = workers.claimLocal();
					if(work == null){ break; }
					active[0]++;
					pool.submit(() -> {
						try{
							var outcome = execute(runner, mark, context, activeWorkers, work);
							context.events.add(() -> {
								active[0]--;
								if(activeWorkers.completeLocal(work, outcome, work.last) && outcome instanceof SequenceOutcome.Failed<S, A> failure){
									context.retainFailure(failure.failure());
								}
							});
						}catch(Throwable e){
							context.events.add(() -> { throw new IllegalStateException("Local fuzz worker failed", e); });
						}
					});
				}
				
				if(workers.isComplete()){
					complete = !context.cancelled;
					break;
				}
				progress.tick();
				Runnable event = context.events.poll(context.waitNanos(), TimeUnit.NANOSECONDS);
				if(event != null) event.run();
			}
			
			results = context.results();
			}
		}catch(InterruptedException e){
			Thread.currentThread().interrupt();
			throw new CancellationException("Master cancelled");
		}finally{
			context.cancelled = true;
			
			if(counter != null) counter.interrupt();
			if(workers != null && !complete) workers.cancel();
			if(pool != null) pool.shutdownNow();
			
			try{
				if(workers != null){
					workers.close();
				}
			}catch(Exception closeFailure){
				// A failed cleanup must never discard the run's collected failures.
				System.err.println("Fuzzer worker cleanup failed: " + closeFailure);
			}finally{
				boolean interrupted = Thread.interrupted();
				try{
					if(pool != null && !pool.awaitTermination(30, TimeUnit.SECONDS)){
						throw new IllegalStateException("Local fuzz workers did not stop after cancellation");
					}
				}catch(InterruptedException e){
					interrupted = true;
				}finally{
					if(interrupted){
						Thread.currentThread().interrupt();
					}
					progress.reportDone(complete);
				}
			}
		}
		return results;
	}
	
	private static <S, A, E extends Throwable> SequenceOutcome<S, A> execute(
		FuzzingRunner<S, A, E> runner, RunMark mark, Context<S, A> context,
		Workers<S, A> workers, Work work
	){
		BooleanSupplier cancelled = () -> context.cancelled || work.cancelled() || Thread.currentThread().isInterrupted();
		if(workers instanceof LocalWorkers && !context.config.shouldLog()){
			return runner.executeSequence(mark, work.sequence(), cancelled);
		}
		return runner.executeSequence(mark, work.sequence(), cancelled, sample -> {
			workers.progress(work, sample);
			work.last = sample;
		});
	}
}
