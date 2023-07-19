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
	
	static <Act, Stat> String report(List<? extends FuzzFail<Act, Stat>> fails){
		if(fails.isEmpty()) return "";
		if(fails.size() == 1){
			return fails.get(0).trace();
		}
		
		var sb = new StringBuilder("Multiple fails:\n");
		for(FuzzFail<?, ?> fail : fails){
			sb.append('\t').append(fail.note()).append('\n');
		}
		sb.append("\nFirst fail:\n");
		sb.append(fails.get(0).trace());
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
		public Create{
			Objects.requireNonNull(e);
			Objects.requireNonNull(sequence);
			Objects.requireNonNull(timeToFail);
		}
		
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
		@Override
		public FuzzingRunner.Mark mark(){
			return new FuzzingRunner.Mark(sequence.index(), -1);
		}
		@Override
		public boolean equals(Object o){
			if(this == o) return true;
			if(!(o instanceof Create<?, ?> create)) return false;
			
			if(!permissiveThrowableEquals(e, create.e)) return false;
			return sequence.equals(create.sequence) &&
			       timeToFail.equals(create.timeToFail);
		}
	}
	
	record Action<Actio, State>(
		Throwable e, FuzzSequence sequence, Actio action, long actionIndex, Duration timeToFail, State badState
	) implements FuzzFail<Actio, State>{
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
			  .append(String.valueOf(sequence.index())).append(", actionIndex: (")
			  .append(String.valueOf(actionIndex - sequence.startIndex())).append(")\t").append(String.valueOf(actionIndex))
			  .append(" Action: ").append(String.valueOf(action)).append("\n");
			PrintWriter pw = new PrintWriter(sw);
			e.printStackTrace(pw);
			return sw.toString();
		}
		@Override
		public FuzzingRunner.Mark mark(){
			return new FuzzingRunner.Mark(sequence.index(), actionIndex);
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
		public int localIndex()                                        { return Math.toIntExact(actionIndex - sequence.startIndex()); }
		public Action<Actio, State> withSequence(FuzzSequence sequence){ return new Action<>(e, sequence, action, actionIndex, timeToFail, badState); }
		
	}
	
	private static boolean permissiveThrowableEquals(Throwable ex1, Throwable ex2){
		if(ex1 == null || ex2 == null) return ex1 == null && ex2 == null;
		
		if(!Objects.equals(ex1.getClass(), ex2.getClass())) return false;
		if(!Objects.equals(ex1.getMessage(), ex2.getMessage())) return false;
		{
			var s1 = ex1.getSuppressed();
			var s2 = ex2.getSuppressed();
			if(s1.length != s2.length) return false;
			for(int i = 0; i<s1.length; i++){
				if(!permissiveThrowableEquals(s1[i], s2[i])){
					return false;
				}
			}
		}
		if(!permissiveThrowableEquals(ex1.getCause(), ex2.getCause())) return false;
		
		var s1 = ex1.getStackTrace();
		var s2 = ex2.getStackTrace();
		
		var fzName = FuzzingRunner.class.getName();
		for(int i = 0; i<Math.min(s1.length, s2.length); i++){
			var el1 = s1[i];
			var el2 = s2[i];
			if(
				el1.getClassName().startsWith(fzName) ||
				el2.getClassName().startsWith(fzName)
			) break;
			if(!el1.equals(el2)){
				return false;
			}
		}
		return true;
	}
	
	Throwable e();
	
	Duration timeToFail();
	String note();
	String trace();
	
	FuzzSequence sequence();
	
	FuzzingRunner.Mark mark();
}
