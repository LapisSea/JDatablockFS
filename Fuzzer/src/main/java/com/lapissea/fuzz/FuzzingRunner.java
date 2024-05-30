package com.lapissea.fuzz;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class FuzzingRunner<State, Action, Err extends Throwable>{
	
	private record NOOP() implements Serializable{ }
	
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
		            .filter(f -> !failShort.equals(f))
		            .<Stability>map(f -> new Stability.FailsNotSame(failShort, f))
		            .findAny().orElse(new Stability.Ok(sequence, mark.optAction()));
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
		if(progress != null && progress.hasErr()) return Optional.empty();
		
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
			if(progress != null && progress.hasErr()) return Optional.empty();
			
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
		
		return Optional.empty();
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
		
		return switch(RunType.of(source, stateEnv, mark)){
			case RunType.Noop ignored -> List.of();
			case RunType.Single(var sequence) -> {
				var fail = runSequence(mark, sequence, new FuzzProgress(conf, sequence.iterations()));
				yield fail.stream().toList();
			}
			case RunType.Many(long sequencesToRun, long totalIterations) -> {
				var name     = conf.name().orElseGet(FuzzingRunner::getTaskName);
				int nThreads = (int)Math.max(Math.min(conf.maxWorkers(), sequencesToRun) - 1, 1);
				var progress = new FuzzProgress(conf, totalIterations);
				
				var fails = Collections.synchronizedList(new ArrayList<FuzzFail<State, Action>>());
				
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
					reportFail = f -> {
						progress.errLater();
						
						delayExec.schedule(progress::err, conf.errorDelay().toMillis(), MILLISECONDS);
						fails.add(f);
					};
				}
				
				try(var worker = new ThreadPoolExecutor(nThreads, nThreads, 500, MILLISECONDS,
				                                        new LinkedBlockingQueue<>(), new RunnerFactory(nThreads, name))){
					source.all().filter(sq -> stateEnv.shouldRun(sq, mark)).forEach(sequence -> {
						if(nThreads>1 && worker.getQueue().size()>nThreads*2){
							runSequence(mark, sequence, progress).ifPresent(reportFail);
						}else{
							worker.execute(() -> runSequence(mark, sequence, progress).ifPresent(reportFail));
						}
					});
					
					if(nThreads>1){
						Runnable task;
						while((task = worker.getQueue().poll()) != null){
							task.run();
						}
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
				
				Thread.startVirtualThread(System::gc);
				
				yield FuzzFail.sortFails(fails, conf.failOrder().orElse(null));
			}
		};
	}
	
	private sealed interface RunType{
		static RunType of(FuzzSequenceSource source, FuzzingStateEnv<?, ?, ?> stateEnv, RunMark mark){
			var i = source.all().filter(sequence -> stateEnv.shouldRun(sequence, mark)).iterator();
			
			long         sequencesToRun = 0, totalIterations = 0;
			FuzzSequence sequence       = null;
			while(i.hasNext()){
				sequence = i.next();
				sequencesToRun++;
				totalIterations += sequence.iterations();
			}
			
			if(sequencesToRun == 0) return new Noop();
			if(sequencesToRun == 1) return new Single(sequence);
			return new Many(sequencesToRun, totalIterations);
		}
		
		record Noop() implements RunType{ }
		
		record Single(FuzzSequence sequence) implements RunType{ }
		
		record Many(long sequencesToRun, long totalIterations) implements RunType{ }
	}
	
	private static String getTaskName(){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s -> s.dropWhile(f -> !f.getMethodName().equals("run"))
		                              .dropWhile(f -> f.getClassName().startsWith(FuzzingRunner.class.getPackageName() + "."))
		                              .findAny()
		                              .orElseThrow()
		                              .getMethodName());
	}
	
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
