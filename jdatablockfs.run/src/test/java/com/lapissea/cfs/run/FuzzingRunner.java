package com.lapissea.cfs.run;

import com.lapissea.cfs.logging.Log;

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
			sb.append(fails.get(0).trace());
			return sb.toString();
		}
		
		record Create<Action>(Throwable e, SequenceSrc sequence) implements Fail{
			@Override
			public String note(){
				return "Failed to create at: " + sequence + " - " + e;
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
		
		record Action<Action>(Throwable e, SequenceSrc sequence, Action action, int actionIndex) implements Fail{
			@Override
			public String note(){
				return "Failed to apply action at: " + sequence + ", actionIndex: " + actionIndex + " Action: " + action + " - " + e;
			}
			@Override
			public String trace(){
				StringWriter sw = new StringWriter();
				sw.append("Failed to apply action at: ").append(String.valueOf(sequence)).append(", actionIndex: ").append(String.valueOf(actionIndex)).append(" Action: ").append(String.valueOf(action)).append("\n");
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
		
		record Validate<Action>(Throwable e, SequenceSrc sequence, Action action, int actionIndex) implements Fail{
			@Override
			public String note(){
				return "Failed to validate action at: " + sequence + ", actionIndex: " + actionIndex + " Action: " + action + " - " + e;
			}
			@Override
			public String trace(){
				StringWriter sw = new StringWriter();
				sw.append("Failed to validate action at: ").append(String.valueOf(sequence)).append(", actionIndex: ").append(String.valueOf(actionIndex)).append(" Action: ").append(String.valueOf(action)).append("\n");
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
		void applyAction(State state, long id, Action action) throws Err;
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
			return Optional.of(new Fail.Create<>(e, sequence));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations; actionIndex++){
			if(progress != null && progress.hasErr) return Optional.empty();
			
			var action = actionFactory.apply(rand);
			try{
				stateEnv.applyAction(state, sequence.startIndex + actionIndex, action);
			}catch(Throwable e){
				return Optional.of(new Fail.Action<>(e, sequence, action, actionIndex));
			}
			
			if(progress != null) progress.inc();
		}
		
		return Optional.empty();
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
