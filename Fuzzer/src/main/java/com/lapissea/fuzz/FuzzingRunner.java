package com.lapissea.fuzz;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
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
		
		var start = Instant.now();
		var rand  = new SimpleRandom(sequence.seed());
		
		State state;
		try{
			state = stateEnv.create(rand, sequence.index(), mark);
			Objects.requireNonNull(state, "State can not be null");
		}catch(Throwable e){
			FuzzFail.trimErr(e);
			return Optional.of(new FuzzFail.Create<>(e, sequence, Duration.between(start, Instant.now())));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations(); actionIndex++){
			if(progress != null && progress.hasErr()) return none();
			
			var action = actionFactory.apply(rand);
			if(action == null){
				return Optional.of(new FuzzFail.Create<>(
					new NullPointerException("Action can not be null"),
					sequence, Duration.between(start, Instant.now())
				));
			}
			
			var idx = sequence.startIndex() + actionIndex;
			try{
				stateEnv.applyAction(state, idx, action, mark);
			}catch(Throwable e){
				var duration = Duration.between(start, Instant.now());
				FuzzFail.trimErr(e);
				return Optional.of(new FuzzFail.Action<>(e, sequence, action, idx, duration, state));
			}
			
			if(progress != null) progress.inc();
		}
		
		return none();
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
		final var conf = config == null? new FuzzConfig() : config;
		
		return switch(RunType.of(source, stateEnv, mark, conf.maxWorkers()<=1)){
			case RunType.Noop ignored -> List.of();
			case RunType.Single(var sequence) -> {
				var fail = runSequence(mark, sequence, new FuzzProgress(conf, sequence.iterations()));
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
					case ManyData.Instant d -> new FuzzProgress(conf, d.totalIterations);
				};
				
				var fails = Collections.synchronizedList(new ArrayList<FuzzFail<State, Action>>(){
					@Override
					public boolean add(FuzzFail<State, Action> f){
						return (conf.maxErrorsTracked().isEmpty() || conf.maxErrorsTracked().getAsInt()>this.size()) && super.add(f);
					}
				});
				
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
					var res = FuzzFail.sortFails(fails, conf.failOrder().orElse(null));
					Thread.startVirtualThread(System::gc);
					return res;
				};
				
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
					
					if(data instanceof ManyData.Async async) async.compute.accept(worker);
					
					var desiredBuffer = Math.max(nThreads*4, 8);
					var jobSegments   = new ConcurrentLinkedDeque<>(List.of(source.all().spliterator()));
					while(jobSegments.size()<desiredBuffer){
						var res = jobSegments.stream().map(Spliterator::trySplit).filter(Objects::nonNull).toList();
						if(res.isEmpty()) break;
						jobSegments.addAll(res);
					}
					
					while(data instanceof ManyData.Async async && !async.computeFlags.started){
						try{ Thread.sleep(1); }catch(InterruptedException e){ throw new RuntimeException(e); }
					}
					
					Runnable fuzz = () -> {
						while(true){
							var split = jobSegments.poll();
							if(split == null) return;
							
							var holder = new SequenceHolder();
							while(!progress.hasErr() && holder.sequence == null && split.tryAdvance(seq -> {
								if(stateEnv.shouldRun(seq, mark)) holder.sequence = seq;
							})) ;
							var sequence = holder.sequence;
							if(sequence == null) return;
							
							jobSegments.add(split);
							
							runSequence(mark, sequence, progress).ifPresent(reportFail);
						}
					};
					
					while(!progress.hasErr() && !jobSegments.isEmpty()){
						var workerBatch = IntStream.range(0, jobSegments.size()).mapToObj(__ -> CompletableFuture.runAsync(fuzz, executor)).toList();
						
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
	
	private sealed interface RunType{
		static RunType of(FuzzSequenceSource source, FuzzingStateEnv<?, ?, ?> stateEnv, RunMark mark, boolean alwaysFull){
			instant:
			{
				var start = Instant.now();
				var iter  = source.all().sequential().iterator();
				
				long sequencesToRun = 0, totalIterations = 0;
				var  sequence       = (FuzzSequence)null;
				long count          = 0;
				while(iter.hasNext()){
					if(!alwaysFull && ((++count)%5 == 0) && Duration.between(start, Instant.now()).toMillis()>100){
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
