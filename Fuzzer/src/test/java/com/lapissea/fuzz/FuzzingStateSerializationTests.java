package com.lapissea.fuzz;

import org.testng.annotations.Test;

/**
 * Regression for {@link FuzzingStateEnv.JustRandom} returning a {@link SimpleRandom} as its state, which
 * must therefore be serializable for {@link FuzzingRunner#ensureStateSerializability} and distributed runs.
 */
public class FuzzingStateSerializationTests{
	
	@Test
	public void justRandomStateIsSerializable(){
		var runner = new FuzzingRunner<>(
			FuzzingStateEnv.JustRandom.<Object, Exception>of((random, index, mark) -> { }),
			FuzzingRunner::noopAction);
		runner.ensureStateSerializability(new FuzzSequence(0, 0, 7, 5));
	}
}
