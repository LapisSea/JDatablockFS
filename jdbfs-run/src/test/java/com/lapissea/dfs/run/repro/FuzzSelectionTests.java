package com.lapissea.dfs.run.repro;

import com.lapissea.fuzz.RNGEnum;
import com.lapissea.fuzz.RNGType;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;

public class FuzzSelectionTests{

	private enum Choice{ A, B, C }
	private record A(){}
	private record B(){}
	private record C(){}

	@DataProvider
	Object[][] implementations(){ return new Object[][]{{false}, {true}}; }

	private static Function<RandomGenerator, Choice> picker(boolean types, float... weights){
		if(!types){
			var rng = RNGEnum.of(Choice.class);
			for(int i = 0; i<weights.length; i++) rng.chanceFor(Choice.values()[i], weights[i]);
			return rng;
		}
		var rng = RNGType.<Object>of(List.of(r -> new A(), r -> new B(), r -> new C()));
		var classes = List.of(A.class, B.class, C.class);
		for(int i = 0; i<weights.length; i++) rng.chanceFor(classes.get(i), weights[i]);
		return rng.map((value, random) -> Choice.values()[classes.indexOf(value.getClass())]);
	}

	private static Random roll(float value){
		return new Random(0){
			@Override
			public float nextFloat(){ return value; }
			@Override
			public int nextInt(int bound){ return 0; }
		};
	}

	@Test(dataProvider = "implementations")
	void configuredChancesUseDisjointIntervals(boolean types){
		var rng = picker(types, 0.25F, 0.5F);
		assertEquals(rng.apply(roll(0)), Choice.A);
		assertEquals(rng.apply(roll(Math.nextDown(0.25F))), Choice.A);
		assertEquals(rng.apply(roll(0.25F)), Choice.B);
		assertEquals(rng.apply(roll(Math.nextDown(0.75F))), Choice.B);
		assertEquals(rng.apply(roll(0.75F)), Choice.C);
		assertEquals(rng.apply(roll(Math.nextDown(1F))), Choice.C);
	}

	@Test(dataProvider = "implementations")
	void halfChancesExhaustProbability(boolean types){
		var rng = picker(types, 0.5F, 0.5F);
		assertEquals(rng.apply(roll(0)), Choice.A);
		assertEquals(rng.apply(roll(0.5F)), Choice.B);
		assertEquals(rng.apply(roll(Math.nextDown(1F))), Choice.B);
	}

	@Test(dataProvider = "implementations")
	void fullyConfiguredChoicesUseRelativeWeights(boolean types){
		var rng = picker(types, 0.125F, 0.375F, 0);
		assertEquals(rng.apply(roll(0)), Choice.A);
		assertEquals(rng.apply(roll(0.25F)), Choice.B);
		assertEquals(rng.apply(roll(Math.nextDown(1F))), Choice.B);
	}

	@Test(dataProvider = "implementations")
	void zeroChanceIsNeverSelected(boolean types){
		assertEquals(picker(types, 0).apply(roll(0)), Choice.B);
	}

	@Test(dataProvider = "implementations")
	void noPositiveChoiceIsRejected(boolean types){
		assertThrows(IllegalStateException.class, () -> picker(types, 0, 0, 0).apply(roll(0)));
	}

	@Test(dataProvider = "implementations")
	void invalidChanceIsRejected(boolean types){
		for(float value : new float[]{-1, Float.NaN, Float.POSITIVE_INFINITY}){
			assertThrows(IllegalArgumentException.class, () -> picker(types, value));
		}
	}


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
