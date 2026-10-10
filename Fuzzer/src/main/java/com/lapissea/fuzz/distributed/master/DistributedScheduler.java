package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;

import java.io.Serializable;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class DistributedScheduler{
	
	public record Assignment(UUID jobId, long id, FuzzSequence sequence, boolean speculative) implements Serializable{
		Wire.Assignment wireAssignment(){
			return new Wire.Assignment(jobId, id);
		}
	}
	
	public enum Health{HEALTHY, DISCONNECTED}
	
	public enum Phase{QUEUED, RUNNING}
	
	public enum Resolution{ACCEPTED, RETRY, IGNORED}
	
	public record AttemptSnapshot(Assignment assignment, Phase phase){ }
	
	public record WorkerSnapshot(
		String id, Health health, int concurrency, int queueCapacity,
		long lastResponseNanos, List<AttemptSnapshot> attempts,
		long demandReports, long idleReports, long startWaitReports
	){ }
	
	public record Snapshot(
		UUID jobId, boolean sourceExhausted, boolean cancelled, long resolved,
		long failures, long cancelledAttempts, List<WorkerSnapshot> workers
	){ }
	
	public record Replay(String workerId, RemoteSequenceResult result){ }
	
	private static final class Work{
		final FuzzSequence sequence;
		boolean resolved;
		long    completedOperations;
		Work(FuzzSequence sequence){ this.sequence = sequence; }
	}
	
	private static final class Attempt implements BooleanSupplier{
		
		final Assignment assignment;
		final Work       work;
		final long       createdAt;
		
		Phase phase = Phase.QUEUED;
		volatile boolean retired;
		
		Attempt(Assignment assignment, Work work, long createdAt){
			this.assignment = assignment;
			this.work = work;
			this.createdAt = createdAt;
		}
		@Override
		public boolean getAsBoolean(){ return retired; }
	}
	
	private static final class Worker{
		
		final String id;
		final int    concurrency;
		final int    queueCapacity;
		
		long demandReports;
		long idleReports;
		long startWaitReports;
		
		Health health       = Health.HEALTHY;
		long   lastResponse = System.nanoTime();
		
		final LinkedHashMap<Long, Attempt> attempts = new LinkedHashMap<>();
		
		Worker(String id, int concurrency, int queueCapacity){
			this.id = id;
			this.concurrency = concurrency;
			this.queueCapacity = queueCapacity;
		}
	}
	
	private static final long STEAL_GRACE_NANOS = 250_000_000L;
	
	private final LongSupplier           clock;
	private final UUID                   jobId;
	private final Iterator<FuzzSequence> source;
	private final Map<String, Worker>    workers = new LinkedHashMap<>();
	private final LinkedHashSet<Work>    retry   = new LinkedHashSet<>();
	private final ArrayDeque<Replay>     replays = new ArrayDeque<>();
	
	private long nextAssignment, resolvedCount, failures, cancelledAttempts, completedOperations;
	private boolean exhausted, cancelled;
	
	public DistributedScheduler(UUID jobId, Iterator<FuzzSequence> source){ this(jobId, source, System::nanoTime); }
	public DistributedScheduler(UUID jobId, Iterator<FuzzSequence> source, LongSupplier clock){
		this.jobId = Objects.requireNonNull(jobId);
		this.source = Objects.requireNonNull(source);
		this.clock = Objects.requireNonNull(clock);
	}
	
	public synchronized void registerWorker(String id, int concurrency, int queueCapacity){
		if(concurrency<1 || queueCapacity<1){
			throw new IllegalArgumentException("Positive worker capacities required");
		}
		if(workers.containsKey(id)){
			throw new IllegalArgumentException("Duplicate worker: " + id);
		}
		workers.put(Objects.requireNonNull(id), new Worker(id, concurrency, queueCapacity));
	}
	private Worker worker(String id){
		var w = workers.get(id);
		if(w == null){
			throw new IllegalArgumentException("Unknown worker: " + id);
		}
		return w;
	}
	public synchronized void heartbeat(String id){
		var w = worker(id);
		if(w.health == Health.HEALTHY){
			w.lastResponse = clock.getAsLong();
		}
	}
	
	synchronized void demand(String id, int idle, int awaitingStart){
		var w = workers.get(id);
		if(w == null){ return; }
		
		if(idle<0 || idle>w.concurrency || awaitingStart<0 || awaitingStart>w.concurrency){
			throw new IllegalArgumentException("Invalid worker demand");
		}
		if(cancelled || w.health != Health.HEALTHY){
			return;
		}
		
		w.demandReports++;
		if(idle>0) w.idleReports++;
		if(awaitingStart>0) w.startWaitReports++;
		
	}
	
	/**
	 * Fill a bounded queue, then steal queued leases or speculate on running tail work.
	 */
	public synchronized List<Assignment> fillQueue(String id){
		Worker worker = worker(id);
		var    added  = new ArrayList<Assignment>();
		
		if(cancelled || worker.health != Health.HEALTHY){
			return List.of();
		}
		
		long queued = worker.attempts.values().stream().filter(a -> a.phase == Phase.QUEUED).count();
		while(queued<worker.queueCapacity){
			Work    work        = null;
			boolean speculative = false;
			
			while(!retry.isEmpty() && work == null){
				var  it        = retry.iterator();
				Work candidate = it.next();
				it.remove();
				if(!candidate.resolved){
					work = candidate;
				}
			}
			
			while(work == null && !exhausted){
				if(!source.hasNext()){
					exhausted = true;
					break;
				}
				work = new Work(source.next());
			}
			
			if(work == null && running(worker) + queued<worker.concurrency){
				outer:
				for(var other : workers.values()){
					if(other == worker || other.health != Health.HEALTHY){
						continue;
					}
					
					var it = other.attempts.values().iterator();
					while(it.hasNext()){
						var a = it.next();
						if(a.phase == Phase.QUEUED &&
						   !has(worker, a.work) &&
						   (running(other)>=other.concurrency || clock.getAsLong() - a.createdAt>=STEAL_GRACE_NANOS)
						){
							work = a.work;
							a.retired = true;
							it.remove();
							
							break outer;
						}
					}
				}
			}
			
			if(work == null && running(worker) + queued<worker.concurrency){
				outer:
				for(var other : workers.values()){
					if(other == worker || other.health != Health.HEALTHY){
						continue;
					}
					for(var a : other.attempts.values()){
						var candidate = a.work;
						if(a.phase == Phase.RUNNING &&
						   !has(worker, candidate) &&
						   attemptCount(candidate)<2
						){
							work = candidate;
							speculative = true;
							break outer;
						}
					}
				}
			}
			
			if(work == null){
				break;
			}
			
			var assignment = new Assignment(jobId, ++nextAssignment, work.sequence, speculative);
			worker.attempts.put(assignment.id(), new Attempt(assignment, work, clock.getAsLong()));
			added.add(assignment);
			
			queued++;
		}
		return List.copyOf(added);
	}
	
	private long running(Worker w)          { return w.attempts.values().stream().filter(a -> a.phase == Phase.RUNNING).count(); }
	private boolean has(Worker w, Work work){ return w.attempts.values().stream().anyMatch(a -> a.work == work); }
	private boolean active(Work work)       { return workers.values().stream().anyMatch(w -> has(w, work)); }
	private long attemptCount(Work work){
		return workers.values().stream().flatMap(w -> w.attempts.values().stream()).filter(a -> a.work == work).count();
	}
	
	public synchronized boolean start(String id, long assignmentId){
		var w = workers.get(id);
		if(w == null){ return false; }
		var a = w.attempts.get(assignmentId);
		if(cancelled || w.health != Health.HEALTHY || a == null || a.phase != Phase.QUEUED){ return false; }
		if(w.attempts.values().stream().filter(v -> v.phase == Phase.RUNNING).count()>=w.concurrency){ return false; }
		a.phase = Phase.RUNNING;
		w.lastResponse = clock.getAsLong();
		return true;
	}
	/**
	 * Credits each operation prefix once per occurrence, across retries and speculative attempts.
	 */
	public synchronized long reportProgress(String id, long assignmentId, long operations){
		var a = worker(id).attempts.get(assignmentId);
		if(a == null || a.phase != Phase.RUNNING || cancelled){ return 0; }
		return creditProgress(a.work, operations);
	}
	private long creditProgress(Work work, long operations){
		if(operations<0 || operations>work.sequence.iterations() || operations<=work.completedOperations){ return 0; }
		long delta = operations - work.completedOperations;
		work.completedOperations = operations;
		completedOperations += delta;
		return delta;
	}
	
	public synchronized long completedOperations(){
		return completedOperations;
	}
	
	public synchronized Resolution complete(String id, RemoteSequenceResult result){ return complete(id, result, true); }
	public synchronized Resolution complete(String id, RemoteSequenceResult result, boolean requireMasterReplay){
		var w = workers.get(id);
		if(w == null || w.health != Health.HEALTHY || cancelled){
			return Resolution.IGNORED;
		}
		
		var a = w.attempts.get(result.assignment().id());
		if(!jobId.equals(result.assignment().job()) || a == null || a.work.resolved || !a.assignment.sequence().equals(result.sequence()) ||
		   result.stats().completedOperations()>a.work.sequence.iterations()){
			return Resolution.IGNORED;
		}
		
		if(a.phase != Phase.RUNNING &&
		   (
			   result.status() instanceof RemoteSequenceResult.Ok ||
			   result.status() instanceof RemoteSequenceResult.Failed ||
			   !result.stats().equals(RemoteSequenceResult.ExecutionStats.EMPTY)
		   )
		){
			return Resolution.IGNORED;
		}
		
		if(a.phase == Phase.RUNNING){
			long operations;
			if(result.status() instanceof RemoteSequenceResult.Ok){
				operations = a.work.sequence.iterations();
			}else{
				operations = result.stats().completedOperations();
			}
			creditProgress(a.work, operations);
		}
		
		a.retired = true;
		w.attempts.remove(result.assignment().id());
		w.lastResponse = clock.getAsLong();
		if(result.status() instanceof RemoteSequenceResult.Ok || result.status() instanceof RemoteSequenceResult.Failed){
			a.work.resolved = true;
			resolvedCount++;
			retry.remove(a.work);
			if(result.status() instanceof RemoteSequenceResult.Failed){
				failures++;
				if(requireMasterReplay){
					replays.add(new Replay(id, result));
				}
			}
			// Retire all losing leases. Late replies are ignored, workers can cancel them
			for(var other : workers.values()){
				other.attempts.values().removeIf(v -> {
					if(v.work != a.work){ return false; }
					v.retired = true;
					return true;
				});
			}
			return Resolution.ACCEPTED;
		}
		
		if(result.status() instanceof RemoteSequenceResult.Cancelled){
			cancelledAttempts++;
		}
		if(!active(a.work)){
			retry.add(a.work);
		}
		return Resolution.RETRY;
	}
	
	public synchronized void disconnect(String id){
		var w = workers.get(id);
		if(w == null){ return; }
		w.health = Health.DISCONNECTED;
		
		var unfinished = w.attempts.values().stream().map(a -> a.work).toList();
		
		w.attempts.values().forEach(a -> a.retired = true);
		w.attempts.clear();
		
		for(var work : unfinished){
			if(!work.resolved && !active(work)){
				retry.add(work);
			}
		}
	}
	
	/**
	 * Capture once before dispatch; each worker check reads only the attempt's volatile retirement flag.
	 */
	synchronized BooleanSupplier cancellationSignal(String id, long assignmentId){
		Worker w = workers.get(id);
		if(w == null){
			return () -> true;
		}
		Attempt a = w.attempts.get(assignmentId);
		if(a == null){
			return () -> true;
		}
		return a;
	}
	
	public synchronized boolean assignmentActive(String id, long assignmentId){
		var w = workers.get(id);
		return w != null && w.attempts.containsKey(assignmentId);
	}
	public synchronized boolean acceptsProgress(String id, long assignmentId, long operations){
		var w = workers.get(id);
		var a = w == null? null : w.attempts.get(assignmentId);
		return !cancelled && a != null && a.phase == Phase.RUNNING && operations>=0 && operations<=a.work.sequence.iterations();
	}
	public synchronized Optional<Replay> takeReplay(){ return Optional.ofNullable(replays.poll()); }
	public synchronized void cancel(){
		cancelled = true;
		retry.clear();
		for(var w : workers.values()){
			cancelledAttempts += w.attempts.size();
			w.attempts.values().forEach(a -> a.retired = true);
			w.attempts.clear();
		}
	}
	public synchronized boolean isComplete(){
		if(cancelled) return true;
		return exhausted &&
		       retry.isEmpty() &&
		       workers.values().stream().allMatch(w -> w.attempts.isEmpty()) &&
		       replays.isEmpty();
	}
	public synchronized Snapshot snapshot(){
		List<WorkerSnapshot> workers = this.workers.values().stream().map(
			w -> new WorkerSnapshot(w.id, w.health, w.concurrency, w.queueCapacity, w.lastResponse,
			                        w.attempts.values().stream().map(a -> new AttemptSnapshot(a.assignment, a.phase)).toList(),
			                        w.demandReports, w.idleReports, w.startWaitReports)
		).toList();
		return new Snapshot(jobId, exhausted, cancelled, resolvedCount, failures, cancelledAttempts, workers);
	}
}
