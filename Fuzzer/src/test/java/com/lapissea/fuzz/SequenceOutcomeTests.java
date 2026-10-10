package com.lapissea.fuzz;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

public class SequenceOutcomeTests{
	private FuzzingRunner<AtomicInteger, Object, RuntimeException> runner(AtomicInteger applied){
		return new FuzzingRunner<>(new FuzzingStateEnv.Marked<>(){
			public AtomicInteger create(RandomGenerator random, long index, RunMark mark)        { return applied; }
			public void applyAction(AtomicInteger state, long index, Object action, RunMark mark){ state.incrementAndGet(); }
		}, FuzzingRunner::noopAction);
	}
	
	@Test
	public void cancellationBeforeCreation(){
		var applied = new AtomicInteger();
		
		assertTrue(runner(applied).executeSequence(RunMark.NONE, new FuzzSequence(0, 0, 42, 10), () -> true) instanceof SequenceOutcome.Cancelled);
		assertEquals(applied.get(), 0);
	}
	
	@Test
	public void cancellationDuringSequenceIsNotSuccess(){
		var applied = new AtomicInteger();
		
		assertTrue(runner(applied).executeSequence(RunMark.NONE, new FuzzSequence(0, 0, 42, 10),
		                                           () -> applied.get()>=3) instanceof SequenceOutcome.Cancelled);
		assertEquals(applied.get(), 3);
	}
	
	@Test
	public void completedSequenceIsSuccess(){
		var applied = new AtomicInteger();
		
		assertTrue(runner(applied).executeSequence(RunMark.NONE, new FuzzSequence(0, 0, 42, 10), () -> false) instanceof SequenceOutcome.Ok);
		assertEquals(applied.get(), 10);
	}
}
