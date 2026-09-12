package com.lapissea.fuzz;

import java.util.ArrayList;
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
	
	private record Chance<E>(Function<RandomGenerator, E> gen, float chance){ }
	
	private final Map<Class<? extends E>, Function<RandomGenerator, E>> universe;
	
	private final List<Function<RandomGenerator, E>> randomPick;
	private final List<Chance<E>>                    chances = new ArrayList<>();
	private       double                             totalChance;
	
	private RNGType(List<Function<RandomGenerator, E>> definition){
		if(definition.isEmpty()) throw new IllegalArgumentException("Definitions required");
		
		var rand = new SimpleRandom(123);
		//noinspection unchecked
		universe = definition.stream()
		                     .collect(Collectors.toUnmodifiableMap(
			                     e -> (Class<? extends E>)e.apply(rand).getClass(),
			                     Function.identity()
		                     ));
		
		randomPick = new ArrayList<>(definition);
	}
	
	@Override
	public E apply(RandomGenerator random){
		return getFn(random).apply(random);
	}
	
	private Function<RandomGenerator, E> getFn(RandomGenerator random){
		if(!chances.isEmpty()){
			if(randomPick.isEmpty() && totalChance == 0){
				throw new IllegalStateException("At least one choice must have a positive chance");
			}
			double remaining = random.nextFloat()*(randomPick.isEmpty()? totalChance : Math.max(1F, totalChance));
			for(var ch : chances){
				if(remaining<ch.chance) return ch.gen;
				remaining -= ch.chance;
			}
		}

		return randomPick.get(random.nextInt(randomPick.size()));
	}
	
	public RNGType<E> chanceFor(Class<? extends E> val, float chance){
		if(!Float.isFinite(chance) || chance<0){
			throw new IllegalArgumentException("Chance must be finite and non-negative");
		}
		var fn = universe.get(Objects.requireNonNull(val));
		if(fn == null){
			var types = universe.keySet().stream().map(Class::getName).collect(Collectors.joining("\n"));
			throw new IllegalArgumentException("Invalid type: " + val.getName() + " available types:\n" + types);
		}
		
		chances.add(new Chance<>(fn, chance));
		randomPick.remove(fn);
		
		totalChance += chance;

		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> {
			var e = RNGType.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
