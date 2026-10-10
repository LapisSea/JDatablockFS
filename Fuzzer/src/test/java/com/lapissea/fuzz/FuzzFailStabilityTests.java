package com.lapissea.fuzz;

import org.testng.annotations.Test;

import java.util.random.RandomGenerator;

import static org.testng.Assert.*;

/**
 * Regression for a deterministically reproducible "create" failure being wrongly reported as unstable,
 * because {@link FuzzFail.Create#equals(Object)} compared the thrown exception by identity.
 */
public class FuzzFailStabilityTests{
	
	private static FuzzingRunner<Object, Object, RuntimeException> createFailingRunner(){
		return new FuzzingRunner<>(new FuzzingStateEnv.Marked<>(){
			@Override public Object create(RandomGenerator random, long index, RunMark mark){ throw new IllegalStateException("create boom"); }
			@Override public void applyAction(Object state, long index, Object action, RunMark mark){ }
		}, FuzzingRunner::noopAction);
	}
	
	@Test
	public void deterministicCreateFailIsStable(){
		var runner = createFailingRunner();
		
		var sequence = new FuzzSequence(0, 0, 7, 5);
		var fail     = runner.runSequence(sequence).orElseThrow();
		
		assertTrue(fail instanceof FuzzFail.Create, fail.getClass().toString());
		
		var stability = runner.establishFailStability(fail, 3);
		assertTrue(stability instanceof FuzzingRunner.Stability.Ok, stability.makeReport());
	}
}
