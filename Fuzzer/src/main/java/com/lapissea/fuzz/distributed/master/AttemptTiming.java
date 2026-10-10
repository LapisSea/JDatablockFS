package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.distributed.RemoteSequenceResult.ExecutionStats;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiPredicate;

public final class AttemptTiming{
	
	private record Key(String worker, long assignment){
		private Key{ Objects.requireNonNull(worker); }
	}
	
	private static final class Entry{
		ExecutionStats seen     = ExecutionStats.EMPTY;
		ExecutionStats credited = ExecutionStats.EMPTY;
	}
	
	private final Map<Key, Entry> entries = new HashMap<>();
	
	public synchronized ExecutionStats accept(String worker, long assignment, ExecutionStats cumulative){
		Objects.requireNonNull(cumulative);
		
		var key      = new Key(worker, assignment);
		var entry    = entries.computeIfAbsent(key, ignored -> new Entry());
		var previous = entry.credited;
		
		if(cumulative.completedOperations()<entry.seen.completedOperations() || cumulative.executionNanos()<entry.seen.executionNanos()){
			return ExecutionStats.EMPTY;
		}
		
		entry.seen = cumulative;
		if(cumulative.completedOperations() == 0){
			return ExecutionStats.EMPTY;
		}
		
		entry.credited = cumulative;
		return new ExecutionStats(
			cumulative.completedOperations() - previous.completedOperations(),
			cumulative.executionNanos() - previous.executionNanos()
		);
	}
	
	public synchronized ExecutionStats finish(String worker, long assignment, ExecutionStats cumulative){
		var delta = accept(worker, assignment, cumulative);
		entries.remove(new Key(worker, assignment));
		return delta;
	}
	
	public synchronized void discardInactive(BiPredicate<String, Long> active){
		entries.keySet().removeIf(key -> !active.test(key.worker(), key.assignment()));
	}
	
	public synchronized void discard(String worker, long assignment){ entries.remove(new Key(worker, assignment)); }
	public synchronized int size()                                  { return entries.size(); }
}
