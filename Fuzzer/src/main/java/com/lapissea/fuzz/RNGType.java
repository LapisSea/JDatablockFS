package com.lapissea.fuzz;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;

public final class RNGType<E> implements Function<RandomGenerator, E>{
	
	public static <E> RNGType<E> of(List<Function<RandomGenerator, E>> definition){
		return new RNGType<>(definition);
	}
	
	private final Map<Class<? extends E>, Function<RandomGenerator, E>> universe;
	private final WeightedPick<Function<RandomGenerator, E>>            pick;
	
	private RNGType(List<Function<RandomGenerator, E>> definition){
		if(definition.isEmpty()) throw new IllegalArgumentException("Definitions required");
		
		var rand = new SimpleRandom(123);
		//noinspection unchecked
		universe = definition.stream()
		                     .collect(Collectors.toUnmodifiableMap(
			                     e -> (Class<? extends E>)e.apply(rand).getClass(),
			                     Function.identity()
		                     ));
		
		pick = new WeightedPick<>(definition);
	}
	
	@Override
	public E apply(RandomGenerator random){
		return pick.pick(random).apply(random);
	}
	
	public RNGType<E> chanceFor(Class<? extends E> val, float chance){
		var fn = universe.get(Objects.requireNonNull(val));
		if(fn == null){
			var types = universe.keySet().stream().map(Class::getName).collect(Collectors.joining("\n"));
			throw new IllegalArgumentException("Invalid type: " + val.getName() + " available types:\n" + types);
		}
		
		pick.chanceFor(fn, chance);
		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> mapper.apply(apply(random), random);
	}
}
