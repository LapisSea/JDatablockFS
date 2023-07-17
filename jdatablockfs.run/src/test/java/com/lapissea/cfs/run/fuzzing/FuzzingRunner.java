package com.lapissea.cfs.run.fuzzing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class FuzzingRunner<State, Action, Err extends Throwable>{
	
	public record Sequence(long startIndex, long index, long seed, int iterations){
		@Override
		public String toString(){
			return String.valueOf(index);
		}
	}
	
	public record LogState(
		float progress,
		Duration estimatedTotalTime, Duration estimatedTimeRemaining,
		Duration elapsed, Duration durationPerOp,
		boolean hasFail
	){ }
	
	@SuppressWarnings("unused")
	public record Config(
		boolean shouldLog, Optional<Consumer<LogState>> logFunct, Duration logTimeout,
		Optional<String> name, int maxWorkers,
		Duration errorDelay
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
				Optional.empty(), Math.max(1, Runtime.getRuntime().availableProcessors()),
				Duration.ofSeconds(2)
			);
		}
		
		public Config dontLog()                           { return new Config(false, Optional.empty(), logTimeout, name, maxWorkers, errorDelay); }
		public Config logWith(Consumer<LogState> logFunct){ return new Config(true, Optional.of(logFunct), logTimeout, name, maxWorkers, errorDelay); }
		public Config withLogTimeout(Duration logTimeout) { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay); }
		public Config withName(String name)               { return new Config(shouldLog, logFunct, logTimeout, Optional.of(name), maxWorkers, errorDelay); }
		public Config withMaxWorkers(int maxWorkers)      { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay); }
		public Config withErrorDelay(Duration errorDelay) { return new Config(shouldLog, logFunct, logTimeout, name, maxWorkers, errorDelay); }
	}
	
	public interface StateEnv<State, Action, Err extends Throwable>{
		boolean shouldRun(Sequence sequence);
		void applyAction(State state, long actionIdx, Action action) throws Err;
		State create(Random random, long sequenceIndex) throws Err;
	}
	
	private final StateEnv<State, Action, Err> stateEnv;
	private final Function<Random, Action>     actionFactory;
	
	public FuzzingRunner(StateEnv<State, Action, Err> stateEnv, Function<Random, Action> actionFactory){
		this.stateEnv = stateEnv;
		this.actionFactory = actionFactory;
	}
	
	public Optional<FuzzFail<Action, State>> run(Sequence sequence){ return run(sequence, null); }
	public Optional<FuzzFail<Action, State>> run(Sequence sequence, FuzzProgress progress){
		
		var start = Instant.now();
		
		var rand = new Random(sequence.seed);
		
		State state;
		try{
			state = stateEnv.create(rand, sequence.index);
		}catch(Throwable e){
			return Optional.of(new FuzzFail.Create<>(e, sequence, Duration.between(start, Instant.now())));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations; actionIndex++){
			if(progress != null && progress.hasErr()) return Optional.empty();
			
			var action = actionFactory.apply(rand);
			var idx    = sequence.startIndex + actionIndex;
			try{
				stateEnv.applyAction(state, idx, action);
			}catch(Throwable e){
				var duration = Duration.between(start, Instant.now());
				return Optional.of(new FuzzFail.Action<>(e, sequence, action, idx, duration, state));
			}
			
			if(progress != null) progress.inc();
		}
		
		return Optional.empty();
	}
	
	public void runAndAssert(long seed, long totalIterations, int sequenceLength){ runAndAssert(null, seed, totalIterations, sequenceLength, null); }
	public void runAndAssert(Config config, long seed, long totalIterations, int sequenceLength, FuzzFail.FailOrder failOrder){
		var fails = run(config, seed, totalIterations, sequenceLength);
		if(!fails.isEmpty()){
			throw new AssertionError(FuzzFail.report(fails, failOrder) + "\nReport stacktrace:");
		}
	}
	
	private static final ScheduledExecutorService DELAY = Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().name("Error delay thread").daemon().unstarted(r));
	
	private record SequenceSource(long seed, long totalIterations, int sequenceLength){
		private Stream<Sequence> all(){
			var sequenceEntropy   = new Random(seed);
			var numberOfSequences = Math.toIntExact(Math.ceilDiv(totalIterations, sequenceLength));
			return IntStream.range(0, numberOfSequences).mapToObj(seqIndex -> {
				var from = seqIndex*sequenceLength;
				var to   = Math.min((seqIndex + 1L)*sequenceLength, totalIterations);
				return new Sequence(from, seqIndex, sequenceEntropy.nextLong(), (int)(to - from));
			});
		}
	}
	
	public List<FuzzFail<Action, State>> run(long seed, long totalIterations, int sequenceLength){
		return run(null, seed, totalIterations, sequenceLength);
	}
	public List<FuzzFail<Action, State>> run(Config config, long seed, long totalIterations, int sequenceLength){
		if(config == null) config = new Config();
		
		var source = new SequenceSource(seed, totalIterations, sequenceLength);
		
		record Info(long sequencesToRun, long totalIterations){ }
		var info = source.all().filter(stateEnv::shouldRun)
		                 .map(s -> new Info(1, s.iterations))
		                 .reduce(new Info(0, 0),
		                         (a, b) -> new Info(a.sequencesToRun + b.sequencesToRun,
		                                            a.totalIterations + b.totalIterations));
		
		if(info.sequencesToRun == 0) return List.of();
		if(info.sequencesToRun == 1){
			var sequence = source.all().filter(stateEnv::shouldRun).findAny().orElseThrow();
			return run(sequence, new FuzzProgress(config, info.totalIterations)).stream().toList();
		}
		
		var name     = config.name.orElseGet(FuzzingRunner::getTaskName);
		var thNum    = new int[1];
		var fails    = Collections.synchronizedList(new ArrayList<FuzzFail<Action, State>>());
		int nThreads = (int)Math.max(Math.min(config.maxWorkers, info.sequencesToRun) - 1, 1);
		try(var worker = new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
			var l    = (nThreads + "").length();
			var sNum = (thNum[0]++) + "";
			return Thread.ofPlatform()
			             .name("fuzzWorker(" + name + ")-" + "0".repeat(Math.max(0, l - sNum.length())) + sNum)
			             .unstarted(r);
		})){
			var millisDelay = config.errorDelay.toMillis();
			var progress    = new FuzzProgress(config, info.totalIterations);
			source.all().filter(stateEnv::shouldRun).forEach(sequence -> {
				Runnable task = () -> {
					if(progress.hasErr()) return;
					run(sequence, progress).ifPresent(e -> {
						if(millisDelay<=0) progress.err();
						else{
							progress.errLater();
							DELAY.schedule(progress::err, millisDelay, TimeUnit.MILLISECONDS);
						}
						fails.add(e);
					});
				};
				if(nThreads>1 && worker.getQueue().size()>nThreads*2){
					task.run();
				}else{
					worker.execute(task);
				}
			});
			
			if(nThreads>1){
				Runnable task;
				while((task = worker.getQueue().poll()) != null){
					task.run();
				}
			}
		}
		
		return List.copyOf(fails);
	}
	
	private static String getTaskName(){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
		                  .walk(s -> s.dropWhile(f -> !f.getMethodName().equals("run"))
		                              .dropWhile(f -> f.getClassName().startsWith(FuzzingRunner.class.getName()))
		                              .findAny()
		                              .orElseThrow()
		                              .getMethodName());
	}
	
}
