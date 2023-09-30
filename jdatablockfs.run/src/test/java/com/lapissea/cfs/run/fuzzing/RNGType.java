package com.lapissea.cfs.run.fuzzing;

import com.lapissea.cfs.utils.RawRandom;

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
	private       float                              totalChance;
	
	private RNGType(List<Function<RandomGenerator, E>> definition){
		if(definition.isEmpty()) throw new IllegalArgumentException("Definitions required");
		
		var rand = new RawRandom(123);
		//noinspection unchecked
		universe = definition.stream()
		                     .collect(Collectors.toUnmodifiableMap(
			                     e -> (Class<? extends E>)e.apply(rand).getClass(),
			                     Function.identity()
		                     ));
		
		randomPick = new ArrayList<>(universe.values());
	}
	
	@Override
	public E apply(RandomGenerator random){
		return getFn(random).apply(random);
	}
	
	private Function<RandomGenerator, E> getFn(RandomGenerator random){
		for(var ch : chances){
			if(random.nextFloat()<=ch.chance){
				return ch.gen;
			}
		}
		if(randomPick.isEmpty()){
			int idx = 0;
			for(float r = random.nextFloat()*totalChance; idx<chances.size() - 1; ++idx){
				r -= chances.get(idx).chance;
				if(r<=0.0) break;
			}
			return chances.get(idx).gen;
		}
		
		return randomPick.get(random.nextInt(randomPick.size()));
	}
	
	public RNGType<E> chanceFor(Class<? extends E> val, float chance){
		var fn = universe.get(Objects.requireNonNull(val));
		if(fn == null) throw new IllegalArgumentException("Invalid type: " + val.getName() + " available types:\n" + universe.keySet().stream().map(Class::getName).collect(Collectors.joining("\n")));
		
		chances.add(new Chance<>(fn, chance));
		randomPick.remove(fn);
		
		if(randomPick.isEmpty()){
			var totalChance = 0F;
			for(var i : chances){
				totalChance += i.chance;
			}
			this.totalChance = totalChance;
		}
		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> {
			var e = RNGType.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
