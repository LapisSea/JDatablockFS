package com.lapissea.fuzz.internal;

import com.lapissea.fuzz.FuzzConfig;
import com.lapissea.fuzz.FuzzFail;
import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.SequenceOutcome;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.testng.Assert.*;

/**
 * Regression for a failing {@link RunCoordinator.Workers#close()} (e.g. a distributed helper that did not
 * clean up) discarding the run's already-collected failures.
 */
public class RunCoordinatorCloseFailureTest{
	
	@Test
	public void resultsSurviveWorkerCloseFailure(){
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			throw new IllegalStateException("boom " + index);
		}), FuzzingRunner::noopAction);
		
		var config = new FuzzConfig().dontLog().withMaxWorkers(1).withErrorDelay(Duration.ofSeconds(10));
		
		var factory = new RunCoordinator.WorkerFactory<RandomGenerator, Object>(){
			@Override
			public RunCoordinator.Workers<RandomGenerator, Object> create(
				RunCoordinator.Context<RandomGenerator, Object> context, Iterator<FuzzSequence> source
			){
				return new RunCoordinator.Workers<>(){
					private boolean done;
					private int     active;
					
					@Override public RunCoordinator.Work claimLocal(){
						if(done || !source.hasNext()){ return null; }
						done = true;
						active++;
						return new RunCoordinator.Work(1, source.next(), () -> false);
					}
					@Override public void progress(RunCoordinator.Work work, FuzzingRunner.SequenceProgress sample){ }
					@Override public boolean completeLocal(
						RunCoordinator.Work work, SequenceOutcome<RandomGenerator, Object> outcome, FuzzingRunner.SequenceProgress sample
					){
						active--;
						if(outcome instanceof SequenceOutcome.Failed<RandomGenerator, Object> failed){ context.retainFailure(failed.failure()); }
						return true;
					}
					@Override public void tick(){ }
					@Override public boolean isComplete(){ return done && active == 0; }
					@Override public void cancel(){ }
					@Override public void close(){ throw new IllegalStateException("cleanup boom"); }
				};
			}
		};
		
		List<FuzzFail<RandomGenerator, Object>> fails = RunCoordinator.run(
			runner, config, RunMark.NONE,
			() -> Stream.of(new FuzzSequence(0, 0, 7, 3)),
			factory
		);
		
		assertEquals(fails.size(), 1);
	}
}
