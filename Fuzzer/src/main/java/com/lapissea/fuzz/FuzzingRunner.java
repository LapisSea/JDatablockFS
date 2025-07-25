package com.lapissea.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fuzz.FuzzConfig.none;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class FuzzingRunner<State, Action, Err extends Throwable>{
	
	private record NOOP() implements Serializable{ }
	
	private static final class SequenceHolder{
		private FuzzSequence sequence;
	}
	
	public static final Object NOOP_ACTION = new NOOP();
	public static Object noopAction(RandomGenerator r){ return NOOP_ACTION; }
	
	private final FuzzingStateEnv<State, Action, Err> stateEnv;
	private final Function<RandomGenerator, Action>   actionFactory;
	
	public FuzzingRunner(FuzzingStateEnv<State, Action, Err> stateEnv, Function<RandomGenerator, Action> actionFactory){
		this.stateEnv = stateEnv;
		this.actionFactory = actionFactory;
	}
	
	public sealed interface Stability{
		record Ok(FuzzSequence sequence, OptionalLong actionIndex) implements Stability{
			public Ok{
				Objects.requireNonNull(sequence);
				Objects.requireNonNull(actionIndex);
			}
			@Override
			public String makeReport(){
				return "Sequence" + sequence + (actionIndex.isPresent()? " on index " + actionIndex.getAsLong() : "") + " is stable";
			}
		}
		
		record DidntFail(int expectedCount, int actualCount) implements Stability{
			public DidntFail{
				if(expectedCount<=0) throw new AssertionError();
				if(expectedCount<=actualCount) throw new AssertionError();
			}
			@Override
			public String makeReport(){
				return
					"Fail not stable had " + expectedCount +
					" reruns but got " + actualCount + " fails";
			}
		}
		
		record FailsNotSame(FuzzFail<?, ?> a, FuzzFail<?, ?> b) implements Stability{
			public FailsNotSame{
				Objects.requireNonNull(a);
				Objects.requireNonNull(b);
			}
			@Override
			public String makeReport(){
				return
					"Fail not stable:\n" +
					a.note() + "\n" +
					b.note() + "\n\n" +
					a.trace() + "\n" +
					b.trace() + "\n" +
					"=========================================================";
			}
		}
		String makeReport();
	}
	
	public void runStable(Stability stability){
		if(Objects.requireNonNull(stability) instanceof Stability.Ok(var sequence, var actionIndex)){
			var mark = new RunMark(sequence.index(), actionIndex.orElse(-1));
			runSequence(mark, sequence);
		}else{
			throw new AssertionError(stability.makeReport());
		}
	}
	public Stability establishFailStability(FuzzFail<State, Action> fail, int reruns){
		Objects.requireNonNull(fail);
		if(reruns<1) throw new IllegalArgumentException("Rerun count must be at least 1");
		
		ensureActionEquality(fail.sequence());
		
		var failShort = shortenFail(fail);
		
		var sequence = failShort.sequence();
		var mark     = failShort.mark();
		
		var fails = run(
			new FuzzConfig().withName("establishStableFail")
			                .withErrorDelay(Duration.ofMillis(Long.MAX_VALUE))
			                .withErrorTracking(new FuzzConfig.ErrorTracking(OptionalInt.empty(), false))
			                .dontLog(),
			RunMark.NONE, () -> IntStream.range(0, reruns).mapToObj(i -> sequence)
		);
		if(fails.size() != reruns){
			return new Stability.DidntFail(reruns, fails.size());
		}
		return fails.stream()
		            .filter(f -> {
			            return !failShort.equals(f);
		            })
		            .<Stability>map(f -> new Stability.FailsNotSame(failShort, f))
		            .findAny().orElse(new Stability.Ok(sequence, mark.optAction()));
	}
	
	public void ensureActionEquality(FuzzSequence sequence){
		var a = actionFactory.apply(new SimpleRandom(sequence.seed()));
		var b = actionFactory.apply(new SimpleRandom(sequence.seed()));
		if(!Objects.equals(a, b) || !Objects.equals(b, a)){
			throw new RuntimeException("Faulty action equality! 2 actions generated from the same random should always be equal!");
		}
	}
	
	public void ensureSerializability(FuzzSequence sequence){
		ensureActionSerializability(sequence);
		ensureStateSerializability(sequence);
	}
	public void ensureStateSerializability(FuzzSequence sequence){
		
		State a;
		try{
			a = stateEnv.create(new SimpleRandom(sequence.seed()), sequence.index(), RunMark.NONE);
		}catch(Throwable e){
			throw new RuntimeException("Failed to create state", e);
		}
		Objects.requireNonNull(a, "State can not be null");
		State b;
		try{
			byte[] aData;
			{
				var buff = new ByteArrayOutputStream();
				try(var out = new ObjectOutputStream(buff)){
					out.writeObject(a);
				}
				aData = buff.toByteArray();
			}
			try(var in = new ObjectInputStream(new ByteArrayInputStream(aData))){
				//noinspection unchecked
				b = (State)in.readObject();
			}
		}catch(NotSerializableException e){
			throw new RuntimeException("State is required to be serializable", e);
		}catch(Throwable e){
			throw new RuntimeException("State is required to be serializable but failed", e);
		}
		
		if(!Objects.equals(a, b) || !Objects.equals(b, a)){
			throw new RuntimeException("Faulty state equality or serializability! A state that is serialized should always be deserialized in to an equal object!");
		}
	}
	public void ensureActionSerializability(FuzzSequence sequence){
		Action a = actionFactory.apply(new SimpleRandom(sequence.seed()));
		Action b;
		try{
			var buff = new ByteArrayOutputStream();
			try(var out = new ObjectOutputStream(buff)){
				out.writeObject(a);
			}
			try(var in = new ObjectInputStream(new ByteArrayInputStream(buff.toByteArray()))){
				//noinspection unchecked
				b = (Action)in.readObject();
			}
		}catch(NotSerializableException e){
			throw new RuntimeException("Action is required to be serializable", e);
		}catch(Throwable e){
			throw new RuntimeException("Action is required to be serializable but failed", e);
		}
		
		if(!Objects.equals(a, b) || !Objects.equals(b, a)){
			throw new RuntimeException("Faulty action equality or serializability! An action that is serialized should always be deserialized in to an equal object!");
		}
	}
	
	private FuzzFail<State, Action> shortenFail(FuzzFail<State, Action> fail){
		if(!(fail instanceof FuzzFail.Action<Action, State> action)){
			return fail;
		}
		var sq       = fail.sequence();
		var iters    = Math.min(sq.iterations(), action.localIndex() + 20);
		var sequence = sq.withIterations(iters);
		return new FuzzFail.Action<>(action.e(), sequence, action.action(), action.actionIndex(), action.timeToFail(), action.badState());
	}
	
	public Optional<FuzzFail<State, Action>> runSequence(FuzzSequence sequence)              { return runSequence(RunMark.NONE, sequence, null); }
	public Optional<FuzzFail<State, Action>> runSequence(RunMark mark, FuzzSequence sequence){ return runSequence(mark, sequence, null); }
	public Optional<FuzzFail<State, Action>> runSequence(RunMark mark, FuzzSequence sequence, FuzzProgress progress){
		if(progress != null && progress.hasErr()) return none();
		
		var start = NanoClock.now();
		var rand  = new SimpleRandom(sequence.seed());
		
		State state;
		try{
			state = stateEnv.create(rand, sequence.index(), mark);
			Objects.requireNonNull(state, "State can not be null");
		}catch(Throwable e){
			FuzzFail.trimErr(e);
			return Optional.of(new FuzzFail.Create<>(e, sequence, Duration.between(start, NanoClock.now())));
		}
		
		var record = progress != null && progress.config.shouldLog()? new ProgressRecord(progress, sequence.iterations()) : null;
		
		for(var actionIndex = 0; actionIndex<sequence.iterations(); actionIndex++){
			if(progress != null && progress.hasErr()) return none();
			
			var action = actionFactory.apply(rand);
			if(action == null){
				var e = new NullPointerException("Action can not be null");
				FuzzFail.trimErr(e);
				return Optional.of(new FuzzFail.Create<>(
					e,
					sequence, Duration.between(start, NanoClock.now())
				));
			}
			
			var incOff = record == null? 0 : actionIndex - record.lastInc;
			
			var idx = sequence.startIndex() + actionIndex;
			try{
				stateEnv.applyAction(state, idx, action, mark);
			}catch(Throwable e){
				if(record != null) record.reportFinal(false);
				var duration = Duration.between(start, NanoClock.now());
				FuzzFail.trimErr(e);
				return Optional.of(new FuzzFail.Action<>(e, sequence, action, idx, duration, state));
			}
			
			if(record != null && incOff>=record.incPeriod) record.report(incOff, actionIndex);
		}
		
		if(record != null) record.reportFinal(true);
		
		return none();
	}
	
	private static final class ProgressRecord{
		private final FuzzProgress progress;
		private final int          iterations;
		
		private int  lastInc     = 0;
		private long lastIncTime = System.nanoTime();
		
		private       int  incPeriod;
		private final long desiredMsReportPeriod;
		
		private ProgressRecord(FuzzProgress progress, int iterations){
			this.progress = progress;
			this.iterations = iterations;
			incPeriod = progress.estimatedIncrementPeriod();
			desiredMsReportPeriod = Math.max(10, progress.config.logTimeout().toMillis()/5);
		}
		
		private void report(int incOff, int actionIndex){
			lastInc = actionIndex;
			long t     = System.nanoTime();
			var  durNs = t - lastIncTime;
			lastIncTime = t;
			
			progress.reportSteps(incOff, durNs);
			incPeriod = adjustIncrementPeriod(durNs);
		}
		
		private void reportFinal(boolean adjust){
			var durNs = System.nanoTime() - lastIncTime;
			if(adjust) incPeriod = Math.max(adjustIncrementPeriod(durNs), 2);
			progress.reportStepsAndPeriod(iterations - lastInc, durNs, incPeriod);
		}
		
		private int adjustIncrementPeriod(long durationNs){
			var duration = durationNs/1000_000;
			if(duration<desiredMsReportPeriod){
				if(duration<desiredMsReportPeriod/3) return incPeriod*2;
				else return incPeriod + 1;
			}else if(incPeriod>2){
				if(duration>desiredMsReportPeriod*3) return Math.max(2, incPeriod/2);
				else return incPeriod - 1;
			}
			return incPeriod;
		}
	}
	
	public void runAndAssert(String sequenceStick){
		runAndAssert(FuzzSequence.fromDataStick(sequenceStick));
	}
	public void runAndAssert(FuzzSequence sequence){
		var errO = runSequence(sequence);
		if(errO.isEmpty()) return;
		var err = errO.get();
		System.err.println(FuzzFail.report(List.of(err)));
		var err2 = runSequence(err.mark(), sequence, null);
		throw new AssertionError(err2.orElse(err));
	}
	
	public void runAndAssert(long seed, long totalIterations, int sequenceLength){ runAndAssert(null, seed, totalIterations, sequenceLength); }
	public void runAndAssert(FuzzConfig config, long seed, long totalIterations, int sequenceLength){
		Plan.start(this, config, seed, totalIterations, sequenceLength)
		    .runAll()
		    .assertFail();
	}
	
	public List<FuzzFail<State, Action>> run(long seed, long totalIterations, int sequenceLength){
		return run(null, seed, totalIterations, sequenceLength);
	}
	public List<FuzzFail<State, Action>> run(FuzzConfig config, long seed, long totalIterations, int sequenceLength){
		return run(config, RunMark.NONE, new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength));
	}
	public List<FuzzFail<State, Action>> run(FuzzConfig config, RunMark mark, long seed, long totalIterations, int sequenceLength){
		return run(config, mark, new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength));
	}
	public List<FuzzFail<State, Action>> run(FuzzConfig config, RunMark mark, FuzzSequenceSource source){
		final var conf = config == null? new FuzzConfig().withName(getTaskName()) : config;
		
		return switch(RunType.of(source, stateEnv, mark, conf.maxWorkers()<=1)){
			case RunType.Noop ignored -> List.of();
			case RunType.Single(var sequence) -> {
				var progress = new FuzzProgress(conf, CompletableFuture.completedFuture((long)sequence.iterations()));
				progress.reportStart();
				var fail = runSequence(mark, sequence, progress);
				progress.reportDone();
				yield fail.stream().toList();
			}
			case RunType.Many(var data) -> {
				var name = conf.name().orElseGet(FuzzingRunner::getTaskName);
				int nThreads = Math.max(1, switch(data){
					case ManyData.Async ignore -> conf.maxWorkers();
					case ManyData.Instant d -> (int)Math.min(conf.maxWorkers(), d.sequencesToRun);
				});
				var progress = switch(data){
					case ManyData.Async d -> new FuzzProgress(conf, d.totalIterations);
					case ManyData.Instant d -> new FuzzProgress(conf, CompletableFuture.completedFuture(d.totalIterations));
				};
				var fails = new ArrayList<FuzzFail<State, Action>>(conf.errorTracking().maxErrorsTracked().orElse(16)){
					synchronized void deduplicateStacktrace(){
						var errC = conf.errorTracking();
						if(!errC.deduplicateByStacktrace()) return;
						var res = FuzzFail.sortFails(this, conf.failOrder().orElse(null));
						clear();
						addAll(res);
						
						var errs = new HashSet<String>();
						removeIf(err -> {
							var sw = new StringBuilder(err.e().getClass().getName());
							for(var e : err.e().getStackTrace()){
								sw.append(e);
							}
							return !errs.add(sw.toString());
						});
					}
					@Override
					public synchronized boolean add(FuzzFail<State, Action> f){
						var errC = conf.errorTracking();
						var errs = errC.maxErrorsTracked();
						if(errs.isEmpty()){
							return super.add(f);
						}
						if(this.size()>=errs.getAsInt()){
							deduplicateStacktrace();
						}
						return errs.getAsInt()>this.size() && super.add(f);
					}
				};
				
				ScheduledExecutorService          delayExec;
				Consumer<FuzzFail<State, Action>> reportFail;
				if(conf.errorDelay().isZero()){
					delayExec = null;
					reportFail = f -> {
						progress.err();
						fails.add(f);
					};
				}else{
					delayExec = Executors.newScheduledThreadPool(
						1, r -> Thread.ofPlatform().name("errorDelayThread(" + name + ")").daemon().unstarted(r));
					var reported = new boolean[]{false};
					reportFail = f -> {
						progress.errLater();
						if(!reported[0]){
							reported[0] = true;
							delayExec.schedule(progress::err, conf.errorDelay().toMillis(), MILLISECONDS);
						}
						fails.add(f);
					};
				}
				
				Supplier<List<FuzzFail<State, Action>>> finalize = () -> {
					progress.reportDone();
					fails.deduplicateStacktrace();
					var res = FuzzFail.sortFails(fails, conf.failOrder().orElse(null));
					Thread.startVirtualThread(System::gc);
					return res;
				};
				
				progress.reportStart();
				
				if(nThreads == 1){
					assert data instanceof ManyData.Instant;
					try{
						source.all().sequential().filter(seq -> stateEnv.shouldRun(seq, mark)).forEach(sequence -> {
							if(progress.hasErr()) throw new HasErr();
							runSequence(mark, sequence, progress).ifPresent(reportFail);
						});
					}catch(HasErr ignore){ }
					yield finalize.get();
				}
				var poolThreads = nThreads - 1;
				try(var executor = new ThreadPoolExecutor(
					poolThreads, poolThreads, 500, MILLISECONDS, new LinkedBlockingQueue<>(), new RunnerFactory(poolThreads, name))
				){
					
					if(data instanceof ManyData.Async async){
						async.compute.accept(task -> executor.execute(() -> {
							progress.reportCusomMsg("Computing stats...");
							task.run();
							progress.reportCusomMsg("Stats computed!");
						}));
					}
					
					var jobsBuild = List.of(source.all().spliterator());
					while(jobsBuild.size()<nThreads){
						var res = jobsBuild.stream().flatMap(s -> Stream.of(s.trySplit(), s)).filter(Objects::nonNull).toList();
						if(jobsBuild.size() == res.size()) break;
						jobsBuild = res;
					}
					
					while(data instanceof ManyData.Async async && !async.computeFlags.started) sleep1();
					
					var heldSegmentCount = new int[]{0};
					var jobSegments      = new ArrayDeque<>(jobsBuild);
					
					Runnable fuzz = () -> {
						var holder = new SequenceHolder();
						while(true){
							Spliterator<FuzzSequence> split;
							findSplit:
							synchronized(jobSegments){
								split = jobSegments.poll();
								if(split != null){
									heldSegmentCount[0]++;
									break findSplit;
								}
								if(heldSegmentCount[0] == 0){//No more segments! Job done.
									return;
								}
								// There is a split that may potentially have more sequences to process.
								// Wait for it to prevent CPU under-utilization
								try{
									jobSegments.wait(100);
								}catch(InterruptedException e){ throw new RuntimeException(e); }
								continue;
							}
							boolean count = true;
							try{
								holder.sequence = null;
								while(!progress.hasErr() && holder.sequence == null && split.tryAdvance(seq -> {
									if(stateEnv.shouldRun(seq, mark)) holder.sequence = seq;
								})) ;
								if(holder.sequence == null) continue;
								
								count = false;
								synchronized(jobSegments){
									jobSegments.addFirst(split);
									heldSegmentCount[0]--;
									jobSegments.notify();
								}
							}finally{
								if(count) synchronized(jobSegments){
									heldSegmentCount[0]--;
									jobSegments.notify();
								}
							}
							
							runSequence(mark, holder.sequence, progress).ifPresent(reportFail);
						}
					};
					
					while(!progress.hasErr() && !jobSegments.isEmpty()){
						var workerBatch = IntStream.range(0, Math.max(jobSegments.size(), poolThreads)).mapToObj(__ -> {
							sleep1();
							return CompletableFuture.runAsync(fuzz, executor);
						}).toList();
						
						fuzz.run();
						
						for(var worker : workerBatch){
							worker.join();
						}
					}
					
					if(data instanceof ManyData.Async async){
						async.computeFlags.stop = true;
					}
					
				}finally{
					if(delayExec != null){
						var tasks = delayExec.shutdownNow();
						if(!tasks.isEmpty()){
							tasks.getFirst().run();
						}
						delayExec.close();
					}
				}
				
				yield finalize.get();
			}
		};
	}
	
	private static void sleep1(){
		try{ Thread.sleep(1); }catch(InterruptedException e){ throw new RuntimeException(e); }
	}
	
	private sealed interface RunType{
		static RunType of(FuzzSequenceSource source, FuzzingStateEnv<?, ?, ?> stateEnv, RunMark mark, boolean alwaysFull){
			instant:
			{
				var start = NanoClock.now();
				var iter  = source.all().sequential().iterator();
				
				long sequencesToRun = 0, totalIterations = 0;
				var  sequence       = (FuzzSequence)null;
				long count          = 0;
				while(iter.hasNext()){
					if(!alwaysFull && ((++count)%5 == 0) && Duration.between(start, NanoClock.now()).toMillis()>100){
						break instant;
					}
					var seq = iter.next();
					if(!stateEnv.shouldRun(seq, mark)) continue;
					sequence = seq;
					sequencesToRun++;
					totalIterations += seq.iterations();
				}
				
				if(sequencesToRun == 0) return new Noop();
				if(sequencesToRun == 1) return new Single(sequence);
				return new Many(new ManyData.Instant(sequencesToRun, totalIterations));
			}
			
			var                     flags           = new ManyData.Async.ComputeFlag();
			CompletableFuture<Long> totalIterations = new CompletableFuture<>();
			return new Many(new ManyData.Async(totalIterations, exec -> {
				exec.execute(() -> {
					flags.started = true;
					long iterSum = 0;
					try{
						var iter = source.all().iterator();
						
						while(iter.hasNext()){
							if(flags.stop){
								break;
							}
							var seq = iter.next();
							if(!stateEnv.shouldRun(seq, mark)) continue;
							iterSum += seq.iterations();
						}
					}finally{
						totalIterations.complete(iterSum);
					}
				});
			}, flags));
		}
		
		record Noop() implements RunType{ }
		
		record Single(FuzzSequence sequence) implements RunType{ }
		
		record Many(ManyData data) implements RunType{ }
	}
	
	sealed interface ManyData{
		record Instant(long sequencesToRun, long totalIterations) implements ManyData{ }
		
		record Async(CompletableFuture<Long> totalIterations, Consumer<Executor> compute, ComputeFlag computeFlags) implements ManyData{
			private static final class ComputeFlag{
				private boolean started, stop;
			}
		}
	}
	
	
	private static String getTaskName(){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s -> s.dropWhile(f -> !f.getMethodName().equals("run"))
		                              .dropWhile(f -> f.getClassName().startsWith(FuzzingRunner.class.getPackageName() + "."))
		                              .findAny()
		                              .orElseThrow()
		                              .getMethodName());
	}
	
	private static final class HasErr extends RuntimeException{ }
	
	private static final class RunnerFactory implements ThreadFactory{
		private       int    threadIndex;
		private final int    numLen;
		private final String prefix;
		
		public RunnerFactory(int nThreads, String name){
			numLen = String.valueOf(nThreads).length();
			this.prefix = "fuzzWorker(" + name + ")-";
		}
		
		@Override
		public Thread newThread(Runnable r){
			var indexStr = String.valueOf(threadIndex++);
			return Thread.ofPlatform()
			             .name(prefix + "0".repeat(Math.max(0, numLen - indexStr.length())) + indexStr)
			             .unstarted(r);
		}
	}
}
