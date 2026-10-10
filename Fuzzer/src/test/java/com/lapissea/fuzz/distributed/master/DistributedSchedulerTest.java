package com.lapissea.fuzz.distributed.master;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.distributed.RemoteSequenceResult;
import com.lapissea.fuzz.distributed.Wire;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

/**
 * Scheduler contract checks.
 */
public final class DistributedSchedulerTest{

	private static FuzzSequence sequence(long index){ return new FuzzSequence(index*10, index, index, 10); }
	private static RemoteSequenceResult result(DistributedScheduler.Assignment a, RemoteSequenceResult.Status status){
		return new RemoteSequenceResult(a.wireAssignment(), a.sequence(), status, RemoteSequenceResult.CapturedOutput.EMPTY);
	}

	private static void check(boolean condition){
		if(!condition){ throw new AssertionError(); }
	}

	@Test
	public void schedulerContracts(){
		boundedAndLazy();
		speculativeFirstWins();
		cancellationAndDisconnect();
		failureReplay();
		repeatedSourceAndSteal();
		freshQueueGrace();
	}

	@Test
	public void demandOnlyRecordsDiagnostics(){
		var s = new DistributedScheduler(UUID.randomUUID(), LongStream.range(0, 1000).mapToObj(DistributedSchedulerTest::sequence).iterator());
		s.registerWorker("remote", 2, 4);
		var original = s.fillQueue("remote");
		for(int i = 0; i<100; i++){ s.demand("remote", 1, 1); }

		var worker = s.snapshot().workers().getFirst();
		check(worker.queueCapacity() == 4);
		check(worker.attempts().size() == 4);
		check(s.fillQueue("remote").isEmpty());
		for(var assignment : original){ check(s.assignmentActive("remote", assignment.id())); }
		check(worker.demandReports() == 100 && worker.idleReports() == 100 && worker.startWaitReports() == 100);
		Assert.expectThrows(IllegalArgumentException.class, () -> s.demand("remote", 3, 0));
		s.cancel();
		s.demand("remote", 2, 0);
		check(s.snapshot().workers().getFirst().demandReports() == 100);
	}

	private static void boundedAndLazy(){
		var count = new int[1];
		Iterator<FuzzSequence> source = new Iterator<>(){
			public boolean hasNext()  { return count[0]<1000000; }
			public FuzzSequence next(){ return sequence(count[0]++); }
		};
		var s = new DistributedScheduler(UUID.randomUUID(), source);
		s.registerWorker("remote", 2, 3);

		check(s.fillQueue("remote").size() == 3);
		check(count[0] == 3);
		check(s.fillQueue("remote").isEmpty());
		var attempts = s.snapshot().workers().getFirst().attempts();
		check(s.start("remote", attempts.get(0).assignment().id()));
		check(s.start("remote", attempts.get(1).assignment().id()));
		check(!s.start("remote", attempts.get(2).assignment().id()));
		check(s.fillQueue("remote").size() == 2);
		check(count[0] == 5);
	}

	private static void speculativeFirstWins(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("slow", 1, 1);
		s.registerWorker("fast", 1, 1);
		var first = s.fillQueue("slow").getFirst();
		check(s.start("slow", first.id()));
		var duplicate = s.fillQueue("fast").getFirst();
		check(duplicate.speculative());
		check(s.start("fast", duplicate.id()));

		check(s.complete("fast", result(duplicate, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(
			s.complete("slow", result(first, new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(0, "Error", "late", "trace")))) ==
			DistributedScheduler.Resolution.IGNORED);
		check(s.snapshot().resolved() == 1);
		check(s.takeReplay().isEmpty());
		check(s.isComplete());
	}

	private static void cancellationAndDisconnect(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0), sequence(1)).iterator());
		s.registerWorker("remote", 1, 2);
		s.registerWorker("local", 1, 2);
		var batch = s.fillQueue("remote");
		s.start("remote", batch.getFirst().id());

		check(s.complete("remote", result(batch.getFirst(), new RemoteSequenceResult.Cancelled("stop"))) == DistributedScheduler.Resolution.RETRY);
		s.disconnect("remote");
		var retry = s.fillQueue("local");
		check(retry.size() == 2);
		check(s.snapshot().resolved() == 0);
		check(s.snapshot().cancelledAttempts() == 1);
		for(var a : retry){
			check(s.start("local", a.id()));
			s.complete("local", result(a, new RemoteSequenceResult.Ok()));
		}
		s.fillQueue("local");
		check(s.isComplete());
	}

	private static void repeatedSourceAndSteal(){
		var sequence = sequence(0);
		var clock    = new long[1];
		var s        = new DistributedScheduler(UUID.randomUUID(), List.of(sequence, sequence).iterator(), () -> clock[0]);
		s.registerWorker("a", 1, 2);
		s.registerWorker("b", 1, 2);
		var original = s.fillQueue("a");
		clock[0] = 250_000_000L;
		var stolen = s.fillQueue("b");

		check(stolen.size() == 1);
		check(!s.start("a", original.getFirst().id()));
		for(var lease : stolen){
			check(s.start("b", lease.id()));
			check(s.complete("b", result(lease, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		}
		var remaining = original.getLast();
		check(s.start("a", remaining.id()));
		check(s.complete("a", result(remaining, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(s.snapshot().resolved() == 2);
		check(s.isComplete());
	}

	private static void freshQueueGrace(){
		var clock = new long[1];
		var s     = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0), sequence(1)).iterator(), () -> clock[0]);
		s.registerWorker("remote", 1, 2);
		s.registerWorker("local", 1, 2);
		var remote = s.fillQueue("remote");

		check(s.fillQueue("local").isEmpty());
		check(s.assignmentActive("remote", remote.getFirst().id()));
		clock[0] = 249_999_999L;
		check(s.fillQueue("local").isEmpty());
		clock[0]++;
		check(s.fillQueue("local").size() == 1);

		var t = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0), sequence(1)).iterator());
		t.registerWorker("remote", 1, 1);
		t.registerWorker("local", 1, 1);
		var a = t.fillQueue("remote").getFirst();
		t.start("remote", a.id());
		var b = t.fillQueue("local").getFirst();
		t.start("local", b.id());
		check(t.fillQueue("local").isEmpty());
	}

	@Test
	public void saturatedWorkersDoNotBounceTailWork(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0), sequence(1), sequence(2)).iterator());
		s.registerWorker("a", 1, 1);
		s.registerWorker("b", 1, 1);
		var a = s.fillQueue("a").getFirst();
		check(s.start("a", a.id()));
		var b = s.fillQueue("b").getFirst();
		check(s.start("b", b.id()));
		var tail = s.fillQueue("a").getFirst();

		for(int i = 0; i<1000; i++){
			check(s.fillQueue("b").isEmpty());
			check(s.fillQueue("a").isEmpty());
			check(s.assignmentActive("a", tail.id()));
		}
		check(s.complete("b", result(b, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		var stolen = s.fillQueue("b").getFirst();
		check(stolen.sequence().equals(tail.sequence()));
		check(!s.assignmentActive("a", tail.id()));
		check(s.start("b", stolen.id()));
		check(s.complete("b", result(stolen, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(s.complete("a", result(a, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(s.isComplete());
	}

	@Test
	public void partialProgressCountsOccurrencesNotAttempts(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("a", 1, 1);
		s.registerWorker("b", 1, 1);
		var first = s.fillQueue("a").getFirst();

		check(s.reportProgress("a", first.id(), 5) == 0); // No execution permission yet.
		check(s.start("a", first.id()));
		check(s.reportProgress("a", first.id(), 3) == 3);
		var duplicate = s.fillQueue("b").getFirst();
		check(s.start("b", duplicate.id()));
		check(s.reportProgress("b", duplicate.id(), 3) == 0);
		check(s.reportProgress("b", duplicate.id(), 5) == 2);
		check(s.reportProgress("a", first.id(), 2) == 0); // Reordered sample.
		check(s.reportProgress("a", first.id(), 11) == 0); // Invalid sample.
		check(s.completedOperations() == 5);
		s.disconnect("a");
		s.disconnect("b");
		s.registerWorker("retry", 1, 1);
		var retry = s.fillQueue("retry").getFirst();
		check(s.start("retry", retry.id()));
		check(s.reportProgress("retry", retry.id(), 4) == 0);
		check(s.reportProgress("retry", retry.id(), 7) == 2);
		check(s.complete("retry", result(retry, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(s.completedOperations() == 10);
		check(s.reportProgress("a", first.id(), 10) == 0);
		check(s.complete("retry", result(retry, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.IGNORED);
		check(s.completedOperations() == 10);
	}

	@Test
	public void equalDescriptorsHaveSeparateProgressAndFailuresStayPartial(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0), sequence(0)).iterator());
		s.registerWorker("a", 2, 2);
		var batch = s.fillQueue("a");

		for(var assignment : batch){
			check(s.start("a", assignment.id()));
			check(s.reportProgress("a", assignment.id(), 2) == 2);
			var failure = new RemoteSequenceResult(assignment.wireAssignment(), assignment.sequence(),
			                                       new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(3, "Error", "failed", "trace")),
			                                       RemoteSequenceResult.CapturedOutput.EMPTY, new RemoteSequenceResult.ExecutionStats(3, 100));
			check(s.complete("a", failure) == DistributedScheduler.Resolution.ACCEPTED);
		}
		check(s.completedOperations() == 6);
		s.cancel();
		check(s.completedOperations() == 6);
	}

	@Test
	public void invalidFinishedReportsDoNotRetireAssignments(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("worker", 1, 1);
		var a = s.fillQueue("worker").getFirst();

		check(s.complete("worker", result(a, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.IGNORED);
		check(s.complete("worker", result(a, new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(0, "Error", "bad", "trace")))) ==
		      DistributedScheduler.Resolution.IGNORED);
		check(s.assignmentActive("worker", a.id()));
		check(!s.acceptsProgress("worker", a.id(), 1));
		check(s.start("worker", a.id()));
		var ok = result(a, new RemoteSequenceResult.Ok());
		check(s.complete("unknown", ok) == DistributedScheduler.Resolution.IGNORED);
		for(var invalid : List.of(
			new RemoteSequenceResult(new Wire.Assignment(UUID.randomUUID(), a.id()), a.sequence(), ok.status(), ok.output()),
			new RemoteSequenceResult(new Wire.Assignment(a.jobId(), a.id() + 1), a.sequence(), ok.status(), ok.output()),
			new RemoteSequenceResult(a.wireAssignment(), sequence(1), ok.status(), ok.output()),
			new RemoteSequenceResult(a.wireAssignment(), a.sequence(), ok.status(), ok.output(), new RemoteSequenceResult.ExecutionStats(11, 100)))){
			check(s.complete("worker", invalid) == DistributedScheduler.Resolution.IGNORED);
			check(s.assignmentActive("worker", a.id()));
			check(s.completedOperations() == 0);
		}
		check(!s.acceptsProgress("worker", a.id(), 11));
		check(s.acceptsProgress("worker", a.id(), 1));
		check(s.complete("worker", ok) == DistributedScheduler.Resolution.ACCEPTED);
		check(s.complete("worker", ok) == DistributedScheduler.Resolution.IGNORED);
		check(s.snapshot().resolved() == 1);
		check(s.completedOperations() == 10);
	}

	@Test
	public void queuedInfrastructureFailureStillRetries(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("worker", 1, 1);
		var a = s.fillQueue("worker").getFirst();

		check(s.complete("worker", result(a, new RemoteSequenceResult.InfrastructureError("Unable to start"))) == DistributedScheduler.Resolution.RETRY);
		check(s.completedOperations() == 0);
		check(s.fillQueue("worker").size() == 1);
	}

	@Test(timeOut = 10000)
	public void cancellationChecksDoNotAcquireSchedulerMonitor() throws Exception{
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("local", 1, 1);
		var a = s.fillQueue("local").getFirst();
		check(s.start("local", a.id()));

		var    signal  = s.cancellationSignal("local", a.id());
		var    checked = new CompletableFuture<Boolean>();
		Thread worker;
		synchronized(s){
			worker = Thread.ofPlatform().start(() -> checked.complete(signal.getAsBoolean()));
			try{ check(!checked.get(5, TimeUnit.SECONDS)); }finally{ s.cancel(); }
		}
		worker.join();
		check(signal.getAsBoolean());
	}

	@Test
	public void resolutionRevokesBothWinningAndSpeculativeSignals(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("local", 1, 1);
		s.registerWorker("helper", 1, 1);
		var original = s.fillQueue("local").getFirst();
		check(s.start("local", original.id()));
		var duplicate = s.fillQueue("helper").getFirst();
		check(s.start("helper", duplicate.id()));

		var local  = s.cancellationSignal("local", original.id());
		var helper = s.cancellationSignal("helper", duplicate.id());
		check(!local.getAsBoolean() && !helper.getAsBoolean());
		check(s.complete("helper", result(duplicate, new RemoteSequenceResult.Ok())) == DistributedScheduler.Resolution.ACCEPTED);
		check(local.getAsBoolean() && helper.getAsBoolean());
	}

	@Test
	public void retriesAndDisconnectsGetIndependentCancellationSignals(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("a", 1, 1);
		var original = s.fillQueue("a").getFirst();
		check(s.start("a", original.id()));

		var firstSignal = s.cancellationSignal("a", original.id());
		check(s.complete("a", result(original, new RemoteSequenceResult.InfrastructureError("retry"))) == DistributedScheduler.Resolution.RETRY);
		check(firstSignal.getAsBoolean());
		var retry       = s.fillQueue("a").getFirst();
		var retrySignal = s.cancellationSignal("a", retry.id());
		check(!retrySignal.getAsBoolean());
		s.disconnect("a");
		check(retrySignal.getAsBoolean());
		s.registerWorker("b", 1, 1);
		var reassigned  = s.fillQueue("b").getFirst();
		var finalSignal = s.cancellationSignal("b", reassigned.id());
		check(!finalSignal.getAsBoolean());
		s.cancel();
		check(finalSignal.getAsBoolean());
		check(s.cancellationSignal("unknown", 123).getAsBoolean());
	}

	@Test
	public void stealingRevokesOnlyTheOldLeaseSignal(){
		var clock = new long[1];
		var s     = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator(), () -> clock[0]);
		s.registerWorker("a", 1, 1);
		s.registerWorker("b", 1, 1);
		var original  = s.fillQueue("a").getFirst();
		var oldSignal = s.cancellationSignal("a", original.id());
		clock[0] = 250_000_000L;
		var stolen = s.fillQueue("b").getFirst();

		check(oldSignal.getAsBoolean());
		check(!s.cancellationSignal("b", stolen.id()).getAsBoolean());
	}

	private static void failureReplay(){
		var s = new DistributedScheduler(UUID.randomUUID(), List.of(sequence(0)).iterator());
		s.registerWorker("remote", 1, 1);
		var a = s.fillQueue("remote").getFirst();
		s.start("remote", a.id());

		var failure = new RemoteSequenceResult.Failed(new RemoteSequenceResult.RemoteFailure(3, "Error", "failure", "trace"));
		check(s.complete("remote", result(a, failure)) == DistributedScheduler.Resolution.ACCEPTED);
		s.fillQueue("remote");
		check(!s.isComplete());
		check(s.takeReplay().orElseThrow().result().status().equals(failure));
		check(s.isComplete());
	}
}
