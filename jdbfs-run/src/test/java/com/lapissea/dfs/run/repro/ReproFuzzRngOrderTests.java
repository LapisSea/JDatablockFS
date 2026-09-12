package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.RNGType;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.random.RandomGenerator;

import static org.testng.Assert.assertEquals;

public final class ReproFuzzRngOrderTests{

	private interface Element{}
	private record First() implements Element{}
	private record Second() implements Element{}
	private record Third() implements Element{}

	private static List<Function<RandomGenerator, Element>> definitions(){
		return List.of(r -> new First(), r -> new Second(), r -> new Third());
	}

	private static void assertPickOrder(RNGType<Element> rng, List<Class<?>> expected){
		for(int i = 0; i<expected.size(); i++){
			int index = i;
			var random = new Random(0){
				@Override
				public int nextInt(int bound){
					assertEquals(bound, expected.size());
					return index;
				}
				@Override
				public float nextFloat(){
					return 0.5F;
				}
			};
			assertEquals(rng.apply(random).getClass(), expected.get(i), "selection index " + i);
		}
	}

	@Test
	void pickOrderFollowsDefinitionOrder(){
		assertPickOrder(RNGType.of(definitions()), List.of(First.class, Second.class, Third.class));
		assertPickOrder(RNGType.of(definitions().reversed()), List.of(Third.class, Second.class, First.class));
	}

	@Test
	void explicitChancePreservesRemainingOrder(){
		var rng = RNGType.of(definitions()).chanceFor(Second.class, 0);
		assertPickOrder(rng, List.of(First.class, Third.class));
	}

	@Test
	void callerMutationDoesNotChangePickOrder(){
		var definitions = new ArrayList<>(definitions());
		var rng = RNGType.of(definitions);
		definitions.clear();
		assertPickOrder(rng, List.of(First.class, Second.class, Third.class));
	}

	@Test
	void seededReplayMatchesDefinitionOrder(){
		var definitions = definitions();
		var rng = RNGType.of(definitions);
		var actual = new Random(42);
		var expected = new Random(42);
		for(int i = 0; i<500; i++){
			var fn = definitions.get(expected.nextInt(definitions.size()));
			assertEquals(rng.apply(actual), fn.apply(expected), "selection " + i);
		}
	}
}
