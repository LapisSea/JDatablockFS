package com.lapissea.fuzz;

import com.lapissea.fuzz.internal.RunCoordinator;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.testng.Assert.*;

public class RunCoordinatorDeadlineTest{
	
	@Test(timeOut = 5000)
	public void failureBetweenDeadlineCheckAndWaitCancelsCleanly(){
		var cancelled = new AtomicBoolean();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			fail("No local work should be claimed");
		}), FuzzingRunner::noopAction);
		var config = new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ZERO);
		var failures = RunCoordinator.run(runner, config, RunMark.NONE,
		                                 () -> Stream.of(new FuzzSequence(0, 0, 7, 1), new FuzzSequence(1, 1, 8, 1)),
		                                 (context, source) -> new RunCoordinator.Workers<RandomGenerator, Object>(){
			@Override
			public RunCoordinator.Work claimLocal(){
				// The loop already checked its deadline. The wait must tolerate one expiring here.
				var error = new IllegalStateException("helper failure");
				FuzzFail.trimErr(error);
				context.retainFailure(new FuzzFail.Create<>(error,
				                                          new FuzzSequence(0, 0, 7, 1), Duration.ZERO));
				return null;
			}
			@Override
			public void progress(RunCoordinator.Work work, FuzzingRunner.SequenceProgress sample){ }
			@Override
			public boolean completeLocal(RunCoordinator.Work work, SequenceOutcome<RandomGenerator, Object> outcome,
			                             FuzzingRunner.SequenceProgress sample){ return true; }
			@Override
			public void tick(){ }
			@Override
			public boolean isComplete(){ return cancelled.get(); }
			@Override
			public void cancel(){ cancelled.set(true); }
			@Override
			public void close(){ }
		});
		
		assertTrue(cancelled.get());
		assertEquals(failures.size(), 1);
		assertEquals(failures.getFirst().e().getMessage(), "helper failure");
	}
}
