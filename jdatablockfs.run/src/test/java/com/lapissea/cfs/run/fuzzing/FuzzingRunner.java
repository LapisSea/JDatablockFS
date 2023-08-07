package com.lapissea.cfs.run.fuzzing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class FuzzingRunner<State, Action, Err extends Throwable>{
	
	public record LogState(
		float progress,
		Duration estimatedTotalTime, Duration estimatedTimeRemaining,
		Duration elapsed, Duration durationPerOp,
		boolean hasFail
	){
		public static String stdTime(Duration tim){
			if(tim == null) return "-:--:--";
			return String.format("%d:%02d:%02d",
			                     tim.toHoursPart(),
			                     tim.toMinutesPart(),
			                     tim.toSecondsPart());
		}
	}
	
	@SuppressWarnings("unused")
	public record Config(
		boolean shouldLog, Optional<Consumer<LogState>> logFunct, Duration logTimeout,
		Optional<String> name, int maxWorkers,
		Duration errorDelay, Optional<FailOrder> failOrder
	){
		public Config{
			Objects.requireNonNull(logFunct);
			if(logTimeout.isNegative()) throw new IllegalArgumentException("logTimeout must be positive");
			if(errorDelay.isNegative()) throw new IllegalArgumentException("errorDelay must be positive");
			if(name.map(String::isEmpty).orElse(false)) throw new IllegalArgumentException("Name can not be empty");
			if(maxWorkers<=0) throw new IllegalArgumentException("maxWorkers must be greater than 0");
		}
		public Config(){
			this(
				true, Optional.empty(), Duration.ofMillis(900),
				Optional.empty(), Math.max(1, Runtime.getRuntime().availableProcessors() + 1),
				Duration.ofSeconds(2), Optional.empty()
			);
		}
		
		public Config dontLog()                           { return new Config(false, Optional.empty(), logTimeout, name, maxWorkers, errorDelay, failOrder); }
		public Config logWith(Consumer<LogState> logFunct){ return new Config(true, Optional.of(logFunct), logTimeout, name, maxWorkers, errorDelay, failOrder); }
		public Config withLogTimeout(Duration logTimeout) { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
		public Config withName(String name)               { return new Config(shouldLog, logFunct, logTimeout, Optional.of(name), maxWorkers, errorDelay, failOrder); }
		public Config withMaxWorkers(int maxWorkers)      { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
		public Config withErrorDelay(Duration errorDelay) { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, failOrder); }
		public Config withFailOrder(FailOrder failOrder)  { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay, Optional.of(failOrder)); }
	}
	
	public record Mark(long sequence, long action){
		public static final Mark NONE = new Mark(-1, -1);
		
		public boolean hasSequence()                  { return sequence != -1; }
		public boolean hasAction()                    { return action != -1; }
		public boolean sequence(FuzzSequence sequence){ return this.sequence == sequence.index(); }
		public boolean sequence(long sequenceIndex)   { return sequence == sequenceIndex; }
		public boolean action(long actionIndex)       { return action == actionIndex; }
	}
	
	public interface StateEnv<State, Action, Err extends Throwable>{
		
		abstract class Marked<State, Action, Err extends Throwable> implements StateEnv<State, Action, Err>{
			@Override
			public boolean shouldRun(FuzzSequence sequence, Mark mark){
				if(!mark.hasSequence()) return true;
				return mark.sequence(sequence);
			}
		}
		
		boolean shouldRun(FuzzSequence sequence, Mark mark);
		void applyAction(State state, long actionIndex, Action action, Mark mark) throws Err;
		State create(Random random, long sequenceIndex, Mark mark) throws Err;
	}
	
	private final StateEnv<State, Action, Err> stateEnv;
	private final Function<Random, Action>     actionFactory;
	
	public FuzzingRunner(StateEnv<State, Action, Err> stateEnv, Function<Random, Action> actionFactory){
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
		switch(stability){
			case Stability.Ok(var sequence, var actionIndex) -> {
				var mark = new Mark(sequence.index(), actionIndex.orElse(-1));
				runSequence(mark, sequence);
			}
			default -> throw new AssertionError(stability.makeReport());
		}
	}
	public Stability establishFailStability(FuzzFail<State, Action> fail, int reruns){
		Objects.requireNonNull(fail);
		if(reruns<1) throw new IllegalArgumentException("Rerun count must be at least 1");
		
		var failShort = shortenFail(fail);
		
		var sequence = failShort.sequence();
		var mark     = failShort.mark();
		
		var fails = run(
			new Config().withName("establishStableFail")
			            .withErrorDelay(Duration.ofMillis(Long.MAX_VALUE))
			            .dontLog(),
			Mark.NONE, () -> IntStream.range(0, reruns).mapToObj(i -> sequence)
		);
		if(fails.size() != reruns){
			return new Stability.DidntFail(reruns, fails.size());
		}
		return fails.stream()
		            .filter(f -> !failShort.equals(f))
		            .<Stability>map(f -> new Stability.FailsNotSame(failShort, f))
		            .findAny().orElse(new Stability.Ok(sequence, mark.hasAction()? OptionalLong.of(mark.action) : OptionalLong.empty()));
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
	
	public Optional<FuzzFail<State, Action>> runSequence(FuzzSequence sequence)           { return runSequence(Mark.NONE, sequence, null); }
	public Optional<FuzzFail<State, Action>> runSequence(Mark mark, FuzzSequence sequence){ return runSequence(mark, sequence, null); }
	public Optional<FuzzFail<State, Action>> runSequence(Mark mark, FuzzSequence sequence, FuzzProgress progress){
		if(progress != null && progress.hasErr()) return Optional.empty();
		
		var start = Instant.now();
		var rand  = new Random(sequence.seed());
		
		State state;
		try{
			state = stateEnv.create(rand, sequence.index(), mark);
		}catch(Throwable e){
			FuzzFail.trimErr(e);
			return Optional.of(new FuzzFail.Create<>(e, sequence, Duration.between(start, Instant.now())));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations(); actionIndex++){
			if(progress != null && progress.hasErr()) return Optional.empty();
			
			var action = actionFactory.apply(rand);
			var idx    = sequence.startIndex() + actionIndex;
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
	public void runAndAssert(Config config, long seed, long totalIterations, int sequenceLength){
		Plan.start(this, config, seed, totalIterations, sequenceLength)
		    .runAll()
		    .assertFail();
	}
	
	public List<FuzzFail<State, Action>> run(long seed, long totalIterations, int sequenceLength){
		return run(null, seed, totalIterations, sequenceLength);
	}
	public List<FuzzFail<State, Action>> run(Config config, long seed, long totalIterations, int sequenceLength){
		return run(config, Mark.NONE, new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength));
	}
	public List<FuzzFail<State, Action>> run(Config config, Mark mark, long seed, long totalIterations, int sequenceLength){
		return run(config, mark, new FuzzSequenceSource.LenSeed(seed, totalIterations, sequenceLength));
	}
	public List<FuzzFail<State, Action>> run(Config config, Mark mark, FuzzSequenceSource source){
		final var conf = config == null? new Config() : config;
		
		var runType = RunType.of(source, stateEnv, mark);
		return switch(runType){
			case RunType.Noop ignored -> { yield List.of(); }
			case RunType.Single(var sequence) -> {
				var fail = runSequence(mark, sequence, new FuzzProgress(conf, sequence.iterations()));
				yield fail.stream().toList();
			}
			case RunType.Many(long sequencesToRun, long totalIterations) -> {
				var name     = conf.name.orElseGet(FuzzingRunner::getTaskName);
				int nThreads = (int)Math.max(Math.min(conf.maxWorkers, sequencesToRun) - 1, 1);
				var progress = new FuzzProgress(conf, totalIterations);
				
				var fails = Collections.synchronizedList(new ArrayList<FuzzFail<State, Action>>());
				
				ScheduledExecutorService          delayExec;
				Consumer<FuzzFail<State, Action>> reportFail;
				if(conf.errorDelay.isZero()){
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
						
						delayExec.schedule(progress::err, conf.errorDelay.toMillis(), MILLISECONDS);
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
							tasks.get(0).run();
						}
						delayExec.close();
					}
				}
				
				Thread.startVirtualThread(System::gc);
				
				yield FuzzFail.sortFails(fails, conf.failOrder.orElse(null));
			}
		};
	}
	
	private sealed interface RunType{
		static RunType of(FuzzSequence sequence){ return new Single(sequence); }
		static RunType of(FuzzSequenceSource source, StateEnv<?, ?, ?> stateEnv, Mark mark){
			return source.all()
			             .filter(sequence -> stateEnv.shouldRun(sequence, mark))
			             .map(RunType::of)
			             .reduce(new RunType.Noop(), RunType::merge);
		}
		
		record Noop() implements RunType{
			@Override
			public RunType merge(RunType other){ return other; }
		}
		
		record Single(FuzzSequence sequence) implements RunType{
			@Override
			public RunType merge(RunType other){
				return switch(other){
					case Noop noop -> this;
					case Many o -> new Many(o.sequencesToRun + 1, o.totalIterations + sequence.iterations());
					case Single o -> new Many(2, sequence.iterations() + o.sequence.iterations());
				};
			}
		}
		
		record Many(long sequencesToRun, long totalIterations) implements RunType{
			@Override
			public RunType merge(RunType other){
				return switch(other){
					case Noop noop -> this;
					case Many o -> new Many(o.sequencesToRun + sequencesToRun, o.totalIterations + totalIterations);
					case Single single -> single.merge(this);
				};
			}
		}
		
		RunType merge(RunType other);
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
