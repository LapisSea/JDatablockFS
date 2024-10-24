package com.lapissea.fuzz;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public sealed interface FuzzFail<State, Act>{
	
	static <Act, Stat> String report(List<? extends FuzzFail<Stat, Act>> fails){
		if(fails.isEmpty()) return "";
		if(fails.size() == 1){
			return fails.getFirst().trace();
		}
		
		var sb = new StringBuilder("Multiple fails:\n");
		for(FuzzFail<?, ?> fail : fails){
			sb.append('\t').append(fail.note()).append('\n');
		}
		sb.append("\nFirst fail:\n");
		sb.append(fails.getFirst().trace());
		return sb.toString();
	}
	
	static <Act, Stat, F extends FuzzFail<Stat, Act>> List<F> sortFails(List<F> fails){ return sortFails(fails, null); }
	static <Act, Stat, F extends FuzzFail<Stat, Act>> List<F> sortFails(List<F> fails, FailOrder order){
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
			                          .sorted(Comparator.<List<F>>comparingInt(f -> -f.size()).thenComparingInt(f -> {
				                          return switch(f.getFirst()){
					                          case FuzzFail.Action<?, ?> a -> a.localIndex();
					                          case FuzzFail.Create<?, ?> c -> -1;
				                          };
			                          }))
			                          .flatMap(Collection::stream)
			                          .toList();
		};
	}
	
	record Create<Action, State>(Throwable e, FuzzSequence sequence, Duration timeToFail) implements FuzzFail<State, Action>, Serializable{
		public Create{
			Objects.requireNonNull(e);
			Objects.requireNonNull(sequence);
			Objects.requireNonNull(timeToFail);
			if(!FuzzFail.isTrimmed(e)) throw new IllegalArgumentException("Throwable must be trimmed");
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
		public RunMark mark(){
			return new RunMark(sequence.index(), -1);
		}
		@Override
		public boolean equals(Object o){
			if(this == o) return true;
			if(!(o instanceof Create<?, ?> create)) return false;
			
			if(!e.equals(create.e)) return false;
			return sequence.equals(create.sequence);
		}
	}
	
	record Action<Actio, State>(
		Throwable e, FuzzSequence sequence, Actio action, long actionIndex, Duration timeToFail, State badState
	) implements FuzzFail<State, Actio>, Serializable{
		public Action{
			Objects.requireNonNull(e);
			Objects.requireNonNull(sequence);
			if(actionIndex<0) throw new IllegalArgumentException("actionIndex must be positive");
			Objects.requireNonNull(timeToFail);
			Objects.requireNonNull(badState);
			if(!FuzzFail.isTrimmed(e)) throw new IllegalArgumentException("Throwable must be trimmed");
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
			  .append(String.valueOf(sequence.index())).append("->").append(sequence.makeDataStick()).append(", actionIndex: (")
			  .append(String.valueOf(actionIndex - sequence.startIndex())).append(")\t").append(String.valueOf(actionIndex))
			  .append(" Action: ").append(String.valueOf(action)).append("\n");
			e.printStackTrace(new PrintWriter(sw));
			return sw.toString();
		}
		@Override
		public RunMark mark(){
			return new RunMark(sequence.index(), actionIndex);
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
			if(!e.equals(e2)) return false;
			
			return actionIndex == actionIndex2 &&
			       sequence.equals(sequence2) &&
			       Objects.equals(action, action2);
		}
		public int localIndex(){ return Math.toIntExact(actionIndex - sequence.startIndex()); }
	}
	
	static void trimErr(Throwable e){
		trimErr(e, new HashSet<>());
	}
	private static void trimErr(Throwable e, Set<Throwable> seen){
		if(!seen.add(e)){
			return;
		}
		var stack  = e.getStackTrace();
		var fzName = FuzzingRunner.class.getPackageName() + ".";
		int end    = 0;
		for(; end<stack.length; end++){
			var el = stack[end];
			if(el.getClassName().startsWith(fzName)){
				break;
			}
		}
		
		//TODO figure why last frame matches the "in fuzzing code" in very rare case
		if(end>0) try{
			var el  = stack[end - 1];
			var typ = Class.forName(el.getClassName());
			if(Arrays.stream(typ.getDeclaredMethods()).filter(Method::isSynthetic)
			         .anyMatch(m -> m.getName().equals(el.getMethodName()))
			){
				end--;
			}
		}catch(ClassNotFoundException ignored){ }
		
		stack = Arrays.copyOf(stack, end);
		
		e.setStackTrace(stack);
		
		for(var er : e.getSuppressed()){
			trimErr(er, seen);
		}
		
		var c = e.getCause();
		if(c != null){
			trimErr(c, seen);
		}
	}
	static boolean isTrimmed(Throwable e){
		return isTrimmed(e, new HashSet<>());
	}
	private static boolean isTrimmed(Throwable e, Set<Throwable> seen){
		seen.add(e);
		
		var stack  = e.getStackTrace();
		var fzName = FuzzingRunner.class.getPackageName() + ".";
		for(var el : stack){
			if(el.getClassName().startsWith(fzName)){
				return false;
			}
		}
		
		for(var er : e.getSuppressed()){
			if(seen.contains(er)) continue;
			if(!isTrimmed(er, seen)){
				return false;
			}
		}
		
		var c = e.getCause();
		if(c != null && !seen.contains(c)){
			return isTrimmed(c, seen);
		}
		
		return true;
	}
	
	Throwable e();
	
	Duration timeToFail();
	String note();
	String trace();
	
	FuzzSequence sequence();
	
	RunMark mark();
}
