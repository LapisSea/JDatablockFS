package com.lapissea.fuzz;

import com.lapissea.fuzz.distributed.RecipeCodec;
import com.lapissea.fuzz.distributed.master.DistributedSession;
import com.lapissea.fuzz.internal.RunCoordinator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.fuzz.FuzzConfig.none;

public final class FuzzingRunner<State, Action, Err extends Throwable>{
	
	private record NOOP() implements Serializable{ }
	
	public static final Object NOOP_ACTION = new NOOP();
	public static Object noopAction(RandomGenerator r){ return NOOP_ACTION; }
	
	public interface Recipe<R extends FuzzingRunner<?, ?, ?>> extends Serializable{
		R create();
	}
	
	private byte[] remoteRecipe;
	
	/**
	 * Creates a {@link #FuzzingRunner} that can run across multiple machines.
	 * The recipe is a serializable lambda and all the values it captures must be serializable.
	 */
	public static <R extends FuzzingRunner<?, ?, ?>> R distributed(Recipe<R> recipe){
		var encoded = RecipeCodec.encode(Objects.requireNonNull(recipe));
		
		@SuppressWarnings("unchecked")
		var frozen = (Recipe<R>)RecipeCodec.decode(encoded);
		
		var runner = Objects.requireNonNull(frozen.create(), "Distributed recipe returned null");
		((FuzzingRunner<?, ?, ?>)runner).remoteRecipe = encoded;
		
		return runner;
	}
	
	private final FuzzingStateEnv<State, Action, Err> stateEnv;
	private final Function<RandomGenerator, Action>   actionFactory;
	
	public FuzzingRunner(FuzzingStateEnv<State, Action, Err> stateEnv, Function<RandomGenerator, Action> actionFactory){
		this.stateEnv = stateEnv;
		this.actionFactory = actionFactory;
	}
	
	public void prepareRun(){
		try{ stateEnv.prepareRun(); }catch(Throwable e){ throw unchecked(e); }
	}
	public void cleanupRun(){
		try{ stateEnv.cleanupRun(); }catch(Throwable e){ throw unchecked(e); }
	}
	
	public <T> T runWithLifecycle(Supplier<T> run){
		Throwable failure = null;
		try{
			prepareRun();
			return run.get();
		}catch(Throwable e){
			failure = e;
			throw unchecked(e);
		}finally{
			try{
				cleanupRun();
			}catch(Throwable cleanup){
				if(failure != null){
					if(failure != cleanup) failure.addSuppressed(cleanup);
				}else{
					throw unchecked(cleanup);
				}
			}
		}
	}
	
	private static RuntimeException unchecked(Throwable e){
		FuzzingRunner.<RuntimeException>throwUnchecked(e);
		throw new AssertionError("unreachable");
	}
	@SuppressWarnings("unchecked")
	private static <E extends Throwable> void throwUnchecked(Throwable e) throws E{ throw (E)e; }
	
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
				if(expectedCount<=0){ throw new AssertionError(); }
				if(expectedCount<=actualCount){ throw new AssertionError(); }
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
		if(reruns<1){ throw new IllegalArgumentException("Rerun count must be at least 1"); }
		
		ensureActionEquality(fail.sequence());
		
		var failShort = shortenFail(fail);
		
		var sequence = failShort.sequence();
		var mark     = failShort.mark();
		
		var fails = runLocal(
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
		State b = serializationRoundTrip("State", a);
		
		if(!Objects.equals(a, b) || !Objects.equals(b, a)){
			throw new RuntimeException(
				"Faulty state equality or serializability! A state that is serialized should always be deserialized in to an equal object!");
		}
	}
	public void ensureActionSerializability(FuzzSequence sequence){
		Action a = actionFactory.apply(new SimpleRandom(sequence.seed()));
		Action b = serializationRoundTrip("Action", a);
		
		if(!Objects.equals(a, b) || !Objects.equals(b, a)){
			throw new RuntimeException(
				"Faulty action equality or serializability! An action that is serialized should always be deserialized in to an equal object!");
		}
	}
	private static <T> T serializationRoundTrip(String what, T value){
		try{
			var buff = new ByteArrayOutputStream();
			try(var out = new ObjectOutputStream(buff)){
				out.writeObject(value);
			}
			try(var in = new ObjectInputStream(new ByteArrayInputStream(buff.toByteArray()))){
				@SuppressWarnings("unchecked")
				var copy = (T)in.readObject();
				return copy;
			}
		}catch(NotSerializableException e){
			throw new RuntimeException(what + " is required to be serializable", e);
		}catch(Throwable e){
			throw new RuntimeException(what + " is required to be serializable but failed", e);
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
	
	public Optional<FuzzFail<State, Action>> runSequence(FuzzSequence sequence){
		return runSequence(RunMark.NONE, sequence, null);
	}
	public Optional<FuzzFail<State, Action>> runSequence(RunMark mark, FuzzSequence sequence){ return runSequence(mark, sequence, null); }
	public Optional<FuzzFail<State, Action>> runSequence(RunMark mark, FuzzSequence sequence, FuzzProgress progress){
		var outcome = executeSequence(mark, sequence, progress, () -> progress != null && progress.hasErr());
		return switch(outcome){
			case SequenceOutcome.Failed<State, Action>(var failure) -> Optional.of(failure);
			case SequenceOutcome.Ok<State, Action> ignored -> none();
			case SequenceOutcome.Cancelled<State, Action> ignored -> none();
		};
	}
	
	public boolean shouldRunSequence(FuzzSequence sequence, RunMark mark){
		return stateEnv.shouldRun(sequence, mark);
	}
	
	public SequenceOutcome<State, Action> executeSequence(RunMark mark, FuzzSequence sequence, BooleanSupplier cancelled){
		return executeSequence(mark, sequence, null, cancelled, null);
	}
	
	public record SequenceProgress(long completedOperations, long executionNanos){ }
	
	public SequenceOutcome<State, Action> executeSequence(RunMark mark, FuzzSequence sequence,
	                                                      BooleanSupplier cancelled, Consumer<SequenceProgress> observer){
		return executeSequence(mark, sequence, null, cancelled, observer);
	}
	
	private SequenceOutcome<State, Action> executeSequence(RunMark mark, FuzzSequence sequence, FuzzProgress progress, BooleanSupplier cancelled){
		return executeSequence(mark, sequence, progress, cancelled, null);
	}
	
	private SequenceOutcome<State, Action> executeSequence(
		RunMark mark, FuzzSequence sequence, FuzzProgress progress,
		BooleanSupplier cancelled, Consumer<SequenceProgress> observer
	){
		var            telemetry = observer == null? null : new ExecutionTelemetry(observer);
		ProgressRecord record    = null;
		int            completed = 0;
		try{
			if(cancelled.getAsBoolean()){
				return new SequenceOutcome.Cancelled<>();
			}
			
			var start = NanoClock.now();
			var rand  = new SimpleRandom(sequence.seed());
			
			State state;
			try{
				state = stateEnv.create(rand, sequence.index(), mark);
				Objects.requireNonNull(state, "State can not be null");
			}catch(Throwable e){
				if(cancelled.getAsBoolean()) return new SequenceOutcome.Cancelled<>();
				
				return failedOutcome(sequence, e, start);
			}
			
			record = progress != null && progress.config.shouldLog()? new ProgressRecord(progress) : null;
			
			for(var actionIndex = 0; actionIndex<sequence.iterations(); actionIndex++){
				if(cancelled.getAsBoolean()) return new SequenceOutcome.Cancelled<>();
				
				Action action;
				try{
					action = actionFactory.apply(rand);
				}catch(Throwable e){
					if(cancelled.getAsBoolean()) return new SequenceOutcome.Cancelled<>();
					return failedOutcome(sequence, e, start);
				}
				if(action == null){
					var e = new NullPointerException("Action can not be null");
					return failedOutcome(sequence, e, start);
				}
				
				var idx = sequence.startIndex() + actionIndex;
				try{
					stateEnv.applyAction(state, idx, action, mark);
				}catch(Throwable e){
					if(cancelled.getAsBoolean()) return new SequenceOutcome.Cancelled<>();
					var duration = Duration.between(start, NanoClock.now());
					FuzzFail.trimErr(e);
					return new SequenceOutcome.Failed<>(new FuzzFail.Action<>(e, sequence, action, idx, duration, state));
				}
				
				completed++;
				if(record != null && completed - record.lastInc>=record.incPeriod) record.report(completed - record.lastInc, completed);
				if(telemetry != null) telemetry.completedAction();
			}
			
			return cancelled.getAsBoolean()? new SequenceOutcome.Cancelled<>() : new SequenceOutcome.Ok<>();
		}finally{
			if(record != null) record.reportFinal(completed, completed == sequence.iterations());
			if(telemetry != null) telemetry.report();
		}
	}
	
	private static <State, Action> SequenceOutcome.Failed<State, Action> failedOutcome(FuzzSequence sequence, Throwable e, Instant start){
		FuzzFail.trimErr(e);
		return new SequenceOutcome.Failed<>(new FuzzFail.Create<>(e, sequence, Duration.between(start, NanoClock.now())));
	}
	
	private static final class ExecutionTelemetry{
		private final Consumer<SequenceProgress> observer;
		private final long                       started = System.nanoTime();
		private       long                       completed, lastReport, callbackNanos;
		
		private ExecutionTelemetry(Consumer<SequenceProgress> observer){
			this.observer = observer;
		}
		
		private void completedAction(){
			completed++;
			long now = System.nanoTime();
			if(completed == 1 || now - lastReport>=200_000_000L) report();
		}
		private void report(){
			long before = System.nanoTime();
			try{
				observer.accept(new SequenceProgress(completed, Math.max(0, before - started - callbackNanos)));
			}finally{
				lastReport = System.nanoTime();
				callbackNanos += lastReport - before;
			}
		}
	}
	
	private static final class ProgressRecord{
		private final FuzzProgress progress;
		
		private int  lastInc     = 0;
		private long lastIncTime = System.nanoTime();
		
		private       int  incPeriod;
		private final long desiredMsReportPeriod;
		
		private ProgressRecord(FuzzProgress progress){
			this.progress = progress;
			incPeriod = progress.estimatedIncrementPeriod();
			desiredMsReportPeriod = Math.max(10, progress.config.logTimeout().toMillis()/5);
		}
		
		private void report(int incOff, int completed){
			lastInc = completed;
			long t     = System.nanoTime();
			var  durNs = t - lastIncTime;
			lastIncTime = t;
			
			progress.reportSteps(incOff, durNs);
			incPeriod = adjustIncrementPeriod(durNs);
		}
		
		private void reportFinal(int completed, boolean adjust){
			var durNs = System.nanoTime() - lastIncTime;
			if(adjust) incPeriod = Math.max(adjustIncrementPeriod(durNs), 2);
			progress.reportStepsAndPeriod(completed - lastInc, durNs, incPeriod);
		}
		
		private int adjustIncrementPeriod(long durationNs){
			var duration = durationNs/1000_000;
			if(duration<desiredMsReportPeriod){
				if(duration<desiredMsReportPeriod/3){
					return incPeriod*2;
				}else{
					return incPeriod + 1;
				}
			}else if(incPeriod>2){
				if(duration>desiredMsReportPeriod*3){
					return Math.max(2, incPeriod/2);
				}else{
					return incPeriod - 1;
				}
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
		if(remoteRecipe != null && mark.equals(RunMark.NONE)){
			return DistributedSession.run(this, remoteRecipe, config == null? new FuzzConfig().withName(getTaskName()) : config, mark, source::all);
		}
		return runLocal(config, mark, source);
	}
	
	public List<FuzzFail<State, Action>> runLocal(FuzzConfig config, RunMark mark, FuzzSequenceSource source){
		var conf = config == null? new FuzzConfig().withName(getTaskName()) : config;
		return runWithLifecycle(() -> RunCoordinator.run(this, conf, mark, source, null));
	}
	
	private static String getTaskName(){
		return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(FuzzingRunner::taskName);
	}
	
	static String taskName(Stream<StackWalker.StackFrame> frames){
		return frames.dropWhile(f -> !f.getMethodName().equals("run"))
		             .dropWhile(f -> f.getClassName().startsWith(FuzzingRunner.class.getPackageName() + "."))
		             .findAny()
		             .map(StackWalker.StackFrame::getMethodName)
		             .orElse("unnamed");
	}
	
}
