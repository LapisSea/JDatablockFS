package com.lapissea.fuzz;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public final class FuzzingRunnerExecutionObserverTest{
	private static final FuzzSequence SEQUENCE = new FuzzSequence(10, 2, 7, 5);
	
	@Test
	public void firstActionAndIntermediateProgressArriveBeforeCompletion() throws Exception{
		var samples = new ArrayList<FuzzingRunner.SequenceProgress>();
		var actions = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			Thread.sleep(75);
			actions.incrementAndGet();
		}), FuzzingRunner::noopAction);
		var outcome = runner.executeSequence(
			RunMark.NONE, SEQUENCE, () -> false, progress -> {
				if(samples.isEmpty()){
					assertEquals(progress.completedOperations(), 1L);
					assertEquals(actions.get(), 1);
				}
				samples.add(progress);
			});
		
		assertTrue(outcome instanceof SequenceOutcome.Ok);
		assertTrue(samples.size()>=3);
		assertEquals(samples.getLast().completedOperations(), 5L);
		for(int i = 1; i<samples.size(); i++){
			assertTrue(samples.get(i).completedOperations()>=samples.get(i - 1).completedOperations());
			assertTrue(samples.get(i).executionNanos()>=samples.get(i - 1).executionNanos());
		}
	}
	
	@Test
	public void terminalFlushCountsSuccessfulActionsForEveryOutcome(){
		var samples = new ArrayList<FuzzingRunner.SequenceProgress>();
		var count   = new AtomicInteger();
		var runner = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> count.incrementAndGet()),
		                                 FuzzingRunner::noopAction);
		
		assertTrue(runner.executeSequence(RunMark.NONE, SEQUENCE, () -> count.get()>=2, samples::add) instanceof SequenceOutcome.Cancelled);
		assertEquals(samples.getLast().completedOperations(), 2L);
		
		samples.clear();
		count.set(0);
		var actionFailure = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> {
			if(count.incrementAndGet() == 3){ throw new IllegalStateException("action"); }
		}), FuzzingRunner::noopAction);
		assertTrue(actionFailure.executeSequence(RunMark.NONE, SEQUENCE, () -> false, samples::add) instanceof SequenceOutcome.Failed);
		assertEquals(samples.getLast().completedOperations(), 2L);
		
		samples.clear();
		count.set(0);
		var factoryFailure = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> { }), random -> {
			if(count.incrementAndGet() == 3){ throw new IllegalStateException("factory"); }
			return new Object();
		});
		assertTrue(factoryFailure.executeSequence(RunMark.NONE, SEQUENCE, () -> false, samples::add) instanceof SequenceOutcome.Failed);
		assertEquals(samples.getLast().completedOperations(), 2L);
		
		samples.clear();
		var createFailure = new FuzzingRunner<>(new FuzzingStateEnv.Marked<Object, Object, Exception>(){
			public Object create(RandomGenerator random, long index, RunMark mark)        { throw new IllegalStateException("create"); }
			public void applyAction(Object state, long index, Object action, RunMark mark){ }
		}, FuzzingRunner::noopAction);
		assertTrue(createFailure.executeSequence(RunMark.NONE, SEQUENCE, () -> false, samples::add) instanceof SequenceOutcome.Failed);
		assertEquals(samples.size(), 1);
		assertEquals(samples.getFirst().completedOperations(), 0L);
		assertTrue(samples.getFirst().executionNanos()>0);
		
		samples.clear();
		assertTrue(runner.executeSequence(RunMark.NONE, SEQUENCE, () -> true, samples::add) instanceof SequenceOutcome.Cancelled);
		assertEquals(samples.size(), 1);
		assertEquals(samples.getFirst().completedOperations(), 0L);
	}
	
	@Test
	public void notificationTimeIsExcludedFromExecutionTiming(){
		var  samples = new ArrayList<FuzzingRunner.SequenceProgress>();
		var  runner  = new FuzzingRunner<>(FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> { }), FuzzingRunner::noopAction);
		long started = System.nanoTime();
		assertTrue(runner.executeSequence(
			RunMark.NONE, SEQUENCE, () -> false, progress -> {
				samples.add(progress);
				try{ Thread.sleep(30); }catch(InterruptedException e){
					throw new AssertionError(e);
				}
			}) instanceof SequenceOutcome.Ok);
		
		long elapsed = System.nanoTime() - started;
		assertEquals(samples.size(), 2);
		assertTrue(elapsed - samples.getLast().executionNanos()>=50_000_000L);
	}
}
