package com.lapissea.cfs.run;

import com.lapissea.cfs.logging.Log;
import org.testng.Assert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class FuzzingRunner<State, Action, Err extends Throwable>{
	
	public record SequenceSrc(long startIndex, long index, long seed, int iterations){
		@Override
		public String toString(){
			return String.valueOf(index);
		}
	}
	
	public sealed interface Fail{
		
		static String report(List<Fail> fails){
			if(fails.isEmpty()) return "";
			if(fails.size() == 1){
				return fails.get(0).trace();
			}
			
			StringBuilder sb = new StringBuilder("Multiple fails:\n");
			for(Fail fail : fails){
				sb.append('\t').append(fail.note()).append('\n');
			}
			sb.append("\nFirst fail:\n");
			sb.append(fails.stream().reduce(fails.get(0), (a, b) -> {
				if(a instanceof Action<?> ac && b instanceof Action<?> bc){
					return ac.actionIndex - ac.sequence.startIndex<bc.actionIndex - bc.sequence.startIndex? ac : bc;
				}
				return a;
			}).trace());
			return sb.toString();
		}
		
		record Create(Throwable e, SequenceSrc sequence) implements Fail{
			@Override
			public String note(){
				return "Failed create - sequence: " + sequence + "\t- " + e;
			}
			@Override
			public String trace(){
				StringWriter sw = new StringWriter();
				sw.append("Failed to create at: ").append(sequence.toString()).append(": ").append("\n");
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
		
		record Action<Action>(Throwable e, SequenceSrc sequence, Action action, long actionIndex) implements Fail{
			@Override
			public String note(){
				return "Failed action - sequence: " + sequence + ",\tactionIndex: " + actionIndex + "\tAction: " + action + "\t- " + e;
			}
			@Override
			public String trace(){
				StringWriter sw = new StringWriter();
				sw.append("Failed to apply action on sequence: ").append(String.valueOf(sequence)).append(", actionIndex: ").append(String.valueOf(actionIndex)).append(" Action: ").append(String.valueOf(action)).append("\n");
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
		
		String note();
		String trace();
		
		SequenceSrc sequence();
	}
	
	public static final class ProgressTracker{
		
		private long    executedCount;
		private int     last    = -1;
		private Instant lastLog = Instant.now();
		private boolean hasErr;
		
		private final double totalIterations;
		public ProgressTracker(long totalIterations){ this.totalIterations = totalIterations; }
		
		void err(){
			hasErr = true;
		}
		
		synchronized void inc(){
			if(!Log.TRACE) return;
			executedCount++;
			var val = (int)(executedCount/totalIterations*1000);
			if(val == last) return;
			
			var now = Instant.now();
			if(Duration.between(lastLog, now).toMillis()>500){
				lastLog = now;
				last = val;
				Log.trace("{}%", String.format("%.1f", val/10D));
			}
		}
	}
	
	public interface StateEnv<State, Action, Err extends Throwable>{
		boolean shouldRun(SequenceSrc sequence);
		void applyAction(State state, long actionIdx, Action action) throws Err;
		State create(Random random) throws Err;
	}
	
	private final StateEnv<State, Action, Err> stateEnv;
	private final Function<Random, Action>     actionFactory;
	
	public FuzzingRunner(StateEnv<State, Action, Err> stateEnv, Function<Random, Action> actionFactory){
		this.stateEnv = stateEnv;
		this.actionFactory = actionFactory;
	}
	
	public Optional<Fail> run(SequenceSrc sequence, ProgressTracker progress){
		
		var rand = new Random(sequence.seed);
		
		State state;
		try{
			state = stateEnv.create(rand);
		}catch(Throwable e){
			return Optional.of(new Fail.Create(e, sequence));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations; actionIndex++){
			if(progress != null && progress.hasErr) return Optional.empty();
			
			var action = actionFactory.apply(rand);
			var idx    = sequence.startIndex + actionIndex;
			try{
				stateEnv.applyAction(state, idx, action);
			}catch(Throwable e){
				return Optional.of(new Fail.Action<>(e, sequence, action, idx));
			}
			
			if(progress != null) progress.inc();
		}
		
		return Optional.empty();
	}
	
	public void runAndAssert(long seed, long totalIterations, int sequenceLength){
		var fails = run(seed, totalIterations, sequenceLength);
		if(!fails.isEmpty()){
			Assert.fail(FuzzingRunner.Fail.report(fails));
		}
	}
	
	public List<Fail> run(long seed, long totalIterations, int sequenceLength){
		var fails = new CopyOnWriteArrayList<Fail>();
		
		Random genesisRand = new Random(seed);
		
		var sequences = Math.ceilDiv(totalIterations, sequenceLength);
		
		var progress = new ProgressTracker(totalIterations);
		
		var cores = Runtime.getRuntime().availableProcessors();
		try(var worker = Executors.newFixedThreadPool((int)Math.min(cores, sequences))){
			for(long sequenceIndex = 0; sequenceIndex<sequences; sequenceIndex++){
				if(!fails.isEmpty()) break;
				
				var from        = sequenceIndex*sequenceLength;
				var to          = Math.min((sequenceIndex + 1)*sequenceLength, totalIterations);
				var actionCount = (int)(to - from);
				
				var seqSeed  = genesisRand.nextLong();
				var sequence = new SequenceSrc(from, sequenceIndex, seqSeed, actionCount);
				if(!stateEnv.shouldRun(sequence)) continue;
				
				worker.execute(() -> {
					if(progress.hasErr) return;
					run(sequence, progress).ifPresent(e -> {
						progress.err();
						fails.add(e);
					});
				});
			}
		}
		
		return List.copyOf(fails);
	}
	
	
}
