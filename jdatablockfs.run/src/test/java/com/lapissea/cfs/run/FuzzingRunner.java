package com.lapissea.cfs.run;

import com.lapissea.cfs.config.ConfigTools;
import com.lapissea.cfs.config.ConfigUtils;
import com.lapissea.cfs.logging.Log;
import org.testng.Assert;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FuzzingRunner<State, Action, Err extends Throwable>{
	
	public record SequenceSrc(long startIndex, long index, long seed, int iterations){
		@Override
		public String toString(){
			return String.valueOf(index);
		}
	}
	
	public sealed interface Fail{
		
		enum FailOrder{
			LEAST_ACTION,
			ORIGINAL_ORDER,
			FAIL_SPEED,
			INDEX,
			COMMON_STACK;
			
			private static FailOrder defaultOrder(){
				return ConfigUtils.configEnum("test.fuzzing.reportFailOrder", FailOrder.COMMON_STACK);
			}
		}
		
		static String report(List<Fail> fails, FailOrder order){
			if(fails.isEmpty()) return "";
			if(fails.size() == 1){
				return fails.get(0).trace();
			}
			
			var sorted = sortFails(fails, order);
			
			var sb = new StringBuilder("Multiple fails:\n");
			for(Fail fail : sorted){
				sb.append('\t').append(fail.note()).append('\n');
			}
			sb.append("\nFirst fail:\n");
			sb.append(sorted.get(0).trace());
			return sb.toString();
		}
		
		static List<Fail> sortFails(List<Fail> fails){ return sortFails(fails, null); }
		static List<Fail> sortFails(List<Fail> fails, FailOrder order){
			return switch(order == null? FailOrder.defaultOrder() : order){
				case LEAST_ACTION -> fails.stream().sorted((a, b) -> {
					if(a instanceof Create && b instanceof Create) return Long.compare(a.sequence().index, b.sequence().index);
					if(a instanceof Create) return -1;
					if(b instanceof Create) return 1;
					
					if(a instanceof Action<?> ac && b instanceof Action<?> bc){
						var cmp = Long.compare(ac.actionIndex - ac.sequence.startIndex, bc.actionIndex - bc.sequence.startIndex);
						if(cmp != 0) return cmp;
					}
					return Long.compare(a.sequence().index, b.sequence().index);
				}).toList();
				case ORIGINAL_ORDER -> fails;
				case FAIL_SPEED -> fails.stream().sorted(Comparator.comparing(Fail::timeToFail)).toList();
				case INDEX -> fails.stream().sorted(Comparator.comparing(f -> f.sequence().index)).toList();
				case COMMON_STACK -> fails.stream()
				                          .collect(Collectors.groupingBy(f -> Arrays.asList(f.e().getStackTrace())))
				                          .values().stream()
				                          .map(l -> sortFails(l, FailOrder.LEAST_ACTION))
				                          .sorted(Comparator.comparingInt(f -> -f.size()))
				                          .flatMap(Collection::stream)
				                          .toList();
			};
		}
		
		record Create(Throwable e, SequenceSrc sequence, Duration timeToFail) implements Fail{
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
		
		record Action<Action>(Throwable e, SequenceSrc sequence, Action action, long actionIndex, Duration timeToFail) implements Fail{
			@Override
			public String note(){
				return "Failed action - sequence: " + sequence +
				       ",\tactionIndex: (" + (actionIndex - sequence.startIndex) + ")\t" +
				       actionIndex + "\tAction: " + action + "\t- " + e;
			}
			@Override
			public String trace(){
				StringWriter sw = new StringWriter();
				sw.append("Failed to apply action on sequence: ")
				  .append(String.valueOf(sequence)).append(", actionIndex: (")
				  .append(String.valueOf(actionIndex - sequence.startIndex)).append(")\t").append(String.valueOf(actionIndex))
				  .append(" Action: ").append(String.valueOf(action)).append("\n");
				PrintWriter pw = new PrintWriter(sw);
				e.printStackTrace(pw);
				return sw.toString();
			}
		}
		
		Throwable e();
		
		Duration timeToFail();
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
		
		var start = Instant.now();
		
		var rand = new Random(sequence.seed);
		
		State state;
		try{
			state = stateEnv.create(rand);
		}catch(Throwable e){
			return Optional.of(new Fail.Create(e, sequence, Duration.between(start, Instant.now())));
		}
		
		for(var actionIndex = 0; actionIndex<sequence.iterations; actionIndex++){
			if(progress != null && progress.hasErr) return Optional.empty();
			
			var action = actionFactory.apply(rand);
			var idx    = sequence.startIndex + actionIndex;
			try{
				stateEnv.applyAction(state, idx, action);
			}catch(Throwable e){
				return Optional.of(new Fail.Action<>(e, sequence, action, idx, Duration.between(start, Instant.now())));
			}
			
			if(progress != null) progress.inc();
		}
		
		return Optional.empty();
	}
	
	public void runAndAssert(long seed, long totalIterations, int sequenceLength){
		runAndAssert(seed, totalIterations, sequenceLength, null);
	}
	public void runAndAssert(long seed, long totalIterations, int sequenceLength, Fail.FailOrder failOrder){
		var fails = run(seed, totalIterations, sequenceLength);
		if(!fails.isEmpty()){
			Assert.fail(FuzzingRunner.Fail.report(fails, failOrder));
		}
	}
	
	private static final ScheduledExecutorService DELAY = Executors.newScheduledThreadPool(1, r -> Thread.ofPlatform().name("Error delay thread").daemon().unstarted(r));
	
	public List<Fail> run(long seed, long totalIterations, int sequenceLength){
		var name = StackWalker.getInstance().walk(s -> s.skip(1).findAny().orElseThrow()).getMethodName();
		
		var fails = new CopyOnWriteArrayList<Fail>();
		
		Random genesisRand = new Random(seed);
		
		var sequences = Math.ceilDiv(totalIterations, sequenceLength);
		
		var progress = new ProgressTracker(totalIterations);
		
		var milisDelay = ConfigTools.flagI("test.fuzzing.errorDelayMs", 2000).positive().resolveVal();
		
		var cores   = Runtime.getRuntime().availableProcessors();
		var builder = Thread.ofPlatform().name("fuzzWorker(" + name + ")-", 0);
		try(var worker = Executors.newFixedThreadPool((int)Math.min(cores, sequences), builder::unstarted)){
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
						if(milisDelay == 0) progress.err();
						else DELAY.schedule(progress::err, milisDelay, TimeUnit.MILLISECONDS);
						fails.add(e);
					});
				});
			}
		}
		
		return List.copyOf(fails);
	}
	
	
}
