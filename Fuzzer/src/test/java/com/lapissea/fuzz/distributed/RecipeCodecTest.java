package com.lapissea.fuzz.distributed;

import com.lapissea.fuzz.FuzzSequence;
import com.lapissea.fuzz.FuzzingRunner;
import com.lapissea.fuzz.FuzzingStateEnv;
import com.lapissea.fuzz.RunMark;
import com.lapissea.fuzz.SequenceOutcome;
import org.testng.annotations.Test;

import java.io.NotSerializableException;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class RecipeCodecTest{
	private static FuzzingRunner<Object, Integer, RuntimeException> runner(int value){
		return new FuzzingRunner<>(new FuzzingStateEnv.Marked<>(){
			public Object create(RandomGenerator random, long sequenceIndex, RunMark mark){ return new Object(); }
			public void applyAction(Object state, long actionIndex, Integer action, RunMark mark){
				if(action != 3){ throw new IllegalStateException("Unexpected captured input: " + action); }
			}
		}, random -> value);
	}
	private static FuzzingRunner<Object, Integer, RuntimeException> defaultRunner(){ return runner(3); }
	
	@Test
	public void staticMethodReferenceNeedsNoSerializableRunnerOrState(){
		var runner = FuzzingRunner.distributed(RecipeCodecTest::defaultRunner);
		
		assertTrue(runner.executeSequence(RunMark.NONE, new FuzzSequence(0, 0, 1, 2), () -> false) instanceof SequenceOutcome.Ok);
	}
	
	@Test
	public void capturedInputsAreFrozenForLocalAndRemoteConstruction(){
		var inputs = new ArrayList<>(List.of(3));
		FuzzingRunner.Recipe<FuzzingRunner<Object, Integer, RuntimeException>>
			recipe = () -> runner(inputs.getFirst());
		var local   = FuzzingRunner.distributed(recipe);
		var encoded = RecipeCodec.encode(recipe);
		inputs.set(0, 4);
		var remote   = RecipeCodec.decode(encoded).create();
		var sequence = new FuzzSequence(0, 0, 1, 2);
		
		assertTrue(local.executeSequence(RunMark.NONE, sequence, () -> false) instanceof SequenceOutcome.Ok);
		assertTrue(remote.executeSequence(RunMark.NONE, sequence, () -> false) instanceof SequenceOutcome.Ok);
	}
	
	@Test
	public void capturesFailEarlyWithActionableError(){
		var input = new Object();
		var error = expectThrows(IllegalArgumentException.class, () -> FuzzingRunner.distributed(() -> runner(input.hashCode())));
		
		assertTrue(error.getMessage().contains("serializable inputs"));
		assertTrue(error.getCause() instanceof NotSerializableException);
	}
	
	@Test
	public void largeAndInvalidRecipesAreRejected(){
		var input = new byte[RecipeCodec.MAX_BYTES + 1];
		
		assertThrows(IllegalArgumentException.class, () -> RecipeCodec.encode(() -> runner(input.length)));
		assertThrows(IllegalArgumentException.class, () -> RecipeCodec.decode(input));
		assertThrows(IllegalArgumentException.class, () -> RecipeCodec.decode(new byte[]{1, 2, 3}));
	}
}
