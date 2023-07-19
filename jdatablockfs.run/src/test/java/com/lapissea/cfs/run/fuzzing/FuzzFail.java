package com.lapissea.cfs.run.fuzzing;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public sealed interface FuzzFail<Act, State>{
	
	static <Act, Stat> Optional<Action<Act, Stat>> findAction(List<? extends FuzzFail<Act, Stat>> fails, Predicate<Action<Act, Stat>> find){
		return fails.stream().filter(a -> a instanceof Action).map(a -> (Action<Act, Stat>)a).filter(find).findFirst();
	}
	
	static <Act, Stat> String report(List<? extends FuzzFail<Act, Stat>> fails){ return report(fails, null); }
	static <Act, Stat> String report(List<? extends FuzzFail<Act, Stat>> fails, FailOrder order){
		if(fails.isEmpty()) return "";
		if(fails.size() == 1){
			return fails.get(0).trace();
		}
		
		var sorted = sortFails(fails, order);
		
		var sb = new StringBuilder("Multiple fails:\n");
		for(FuzzFail<?, ?> fail : sorted){
			sb.append('\t').append(fail.note()).append('\n');
		}
		sb.append("\nFirst fail:\n");
		sb.append(sorted.get(0).trace());
		return sb.toString();
	}
	
	static <Act, Stat, F extends FuzzFail<Act, Stat>> List<F> sortFails(List<F> fails){ return sortFails(fails, null); }
	static <Act, Stat, F extends FuzzFail<Act, Stat>> List<F> sortFails(List<F> fails, FailOrder order){
		return switch(order == null? FailOrder.defaultOrder() : order){
			case LEAST_ACTION -> fails.stream().sorted((a, b) -> {
				if(a instanceof Create && b instanceof Create) return Long.compare(a.sequence().index(), b.sequence().index());
				if(a instanceof Create) return -1;
				if(b instanceof Create) return 1;
				
				if(a instanceof Action<?, ?> ac && b instanceof Action<?, ?> bc){
					var cmp = Integer.compare(ac.localIndex(), bc.localIndex());
					if(cmp != 0) return cmp;
				}
				return Long.compare(a.sequence().index(), b.sequence().index());
			}).toList();
			case ORIGINAL_ORDER -> fails;
			case FAIL_SPEED -> fails.stream().sorted(Comparator.comparing(FuzzFail::timeToFail)).toList();
			case INDEX -> fails.stream().sorted(Comparator.comparing(f -> f.sequence().index())).toList();
			case COMMON_STACK -> fails.stream()
			                          .collect(Collectors.groupingBy(f -> Arrays.asList(f.e().getStackTrace())))
			                          .values().stream()
			                          .map(l -> sortFails(l, FailOrder.LEAST_ACTION))
			                          .sorted(Comparator.comparingInt(f -> -f.size()))
			                          .flatMap(Collection::stream)
			                          .toList();
		};
	}
	
	record Create<Action, State>(Throwable e, FuzzSequence sequence, Duration timeToFail) implements FuzzFail<Action, State>{
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
	
	record Action<Action, State>(Throwable e, FuzzSequence sequence, Action action, long actionIndex, Duration timeToFail, State badState) implements FuzzFail<Action, State>{
		public Action{
			Objects.requireNonNull(e);
			Objects.requireNonNull(sequence);
			Objects.requireNonNull(action);
			if(actionIndex<0) throw new IllegalArgumentException("actionIndex must be positive");
			Objects.requireNonNull(timeToFail);
			Objects.requireNonNull(badState);
		}
		
		@Override
		public String note(){
			return "Failed action - sequence: " + sequence +
			       ",\tactionIndex: (" + (actionIndex - sequence.startIndex()) + ")\t" +
			       actionIndex + "\tAction: " + action + "\t- " + e;
		}
		@Override
		public String trace(){
			StringWriter sw = new StringWriter();
			sw.append("Failed to apply action on sequence: ")
			  .append(String.valueOf(sequence)).append(", actionIndex: (")
			  .append(String.valueOf(actionIndex - sequence.startIndex())).append(")\t").append(String.valueOf(actionIndex))
			  .append(" Action: ").append(String.valueOf(action)).append("\n");
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			return sw.toString();
		}
		
		@Override
		public String toString(){
			return note();
		}
		
		@Override
		public boolean equals(Object o){
			if(this == o) return true;
			if(!(o instanceof FuzzFail.Action<?, ?>(
				Throwable e2, FuzzSequence sequence2, Object action2,
				long actionIndex2, Duration timeToFail2, Object badState2
			))) return false;
			if(!permissiveThrowableEquals(e, e2)) return false;
			
			return actionIndex == actionIndex2 &&
			       sequence.equals(sequence2) &&
			       action.equals(action2);
		}
		
		private boolean permissiveThrowableEquals(Throwable e, Throwable e2){
			if(e == null || e2 == null) return e == null && e2 == null;
			
			if(!Objects.equals(e.getClass(), e2.getClass())) return false;
			if(!Objects.equals(e.getMessage(), e2.getMessage())) return false;
			if(!Arrays.equals(e.getSuppressed(), e2.getSuppressed())) return false;
			if(!permissiveThrowableEquals(e.getCause(), e2.getCause())) return false;
			
			var s1 = e.getStackTrace();
			var s2 = e2.getStackTrace();
			
			for(int i = 0; i<Math.min(s1.length, s2.length); i++){
				var el1 = s1[i];
				var el2 = s2[i];
				if(
					el1.getClassName().startsWith(FuzzingRunner.class.getName()) ||
					el2.getClassName().startsWith(FuzzingRunner.class.getName())
				) break;
				if(!el1.equals(el2)){
					return false;
				}
			}
			return true;
		}
		private int localIndex(){
			return Math.toIntExact(actionIndex - sequence.startIndex());
		}
	}
	
	Throwable e();
	
	Duration timeToFail();
	String note();
	String trace();
	
	FuzzSequence sequence();
}
