package com.lapissea.cfs.run.fuzzing;

import com.lapissea.cfs.config.ConfigUtils;
import org.testng.Assert;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class FuzzingRunner<State, Action, Err extends Throwable>{
	
	public record SequenceSrc(long startIndex, long index, long seed, int iterations){
		@Override
		public String toString(){
			return String.valueOf(index);
		}
	}
	
	public interface StateEnv<State, Action, Err extends Throwable>{
		boolean shouldRun(SequenceSrc sequence);
		void applyAction(State state, long actionIdx, Action action) throws Err;
		State create(Random random, long sequenceIndex) throws Err;
	}
	
	private final StateEnv<State, Action, Err> stateEnv;
	private final Function<Random, Action>     actionFactory;
	
	public FuzzingRunner(StateEnv<State, Action, Err> stateEnv, Function<Random, Action> actionFactory){
		this.stateEnv = stateEnv;
		this.actionFactory = actionFactory;
	}
	
	public Optional<FuzzFail<Action, State>> run(SequenceSrc sequence){ return run(sequence, null); }
	public Optional<FuzzFail<Action, State>> run(SequenceSrc sequence, FuzzProgress progress){
		
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
	
	public void runAndAssert(long seed, long totalIterations, int sequenceLength){ runAndAssert(seed, totalIterations, sequenceLength, null); }
	public void runAndAssert(long seed, long totalIterations, int sequenceLength, FuzzFail.FailOrder failOrder){
		var fails = run(seed, totalIterations, sequenceLength);
		if(!fails.isEmpty()){
			Assert.fail(FuzzFail.report(fails, failOrder) + "\nReport stacktrace:");
		}
	}
	
	private static final ScheduledExecutorService DELAY = Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().name("Error delay thread").daemon().unstarted(r));
	
	public List<FuzzFail<Action, State>> run(long seed, long totalIterations, int sequenceLength){
		var name = StackWalker.getInstance().walk(s -> s.skip(1).findAny().orElseThrow()).getMethodName();
		
		var fails = new CopyOnWriteArrayList<FuzzFail<Action, State>>();
		
		Random genesisRand = new Random(seed);
		
		var sequences = Math.ceilDiv(totalIterations, sequenceLength);
		
		var progress = new FuzzProgress(totalIterations);
		
		var milisDelay = ConfigUtils.configInt("test.fuzzing.errorDelayMs", 2000);
		
		
		var cores    = Runtime.getRuntime().availableProcessors();
		var builder  = Thread.ofPlatform().name("fuzzWorker(" + name + ")-", 0);
		int nThreads = (int)Math.max(Math.min(cores - 1, sequences), 1);
		try(var worker = new ThreadPoolExecutor(nThreads, nThreads,
		                                        0L, TimeUnit.MILLISECONDS,
		                                        new LinkedBlockingQueue<>(),
		                                        builder::unstarted)){
			for(long sequenceIndex = 0; sequenceIndex<sequences; sequenceIndex++){
				if(!fails.isEmpty()) break;
				
				var from        = sequenceIndex*sequenceLength;
				var to          = Math.min((sequenceIndex + 1)*sequenceLength, totalIterations);
				var actionCount = (int)(to - from);
				
				var seqSeed  = genesisRand.nextLong();
				var sequence = new SequenceSrc(from, sequenceIndex, seqSeed, actionCount);
				if(!stateEnv.shouldRun(sequence)) continue;
				
				worker.execute(() -> {
					if(progress.hasErr()) return;
					run(sequence, progress).ifPresent(e -> {
						if(milisDelay<=0) progress.err();
						else DELAY.schedule(progress::err, milisDelay, TimeUnit.MILLISECONDS);
						fails.add(e);
					});
				});
			}
			if(nThreads>1){
				while(true){
					Runnable task = worker.getQueue().poll();
					if(task == null) break;
					task.run();
				}
			}
		}
		
		return List.copyOf(fails);
	}
	
	
}
