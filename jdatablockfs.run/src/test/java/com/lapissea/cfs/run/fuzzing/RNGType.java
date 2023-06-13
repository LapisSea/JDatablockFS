package com.lapissea.cfs.run.fuzzing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toUnmodifiableMap;

public final class RNGType<E> implements Function<Random, E>{
	
	public static <E> RNGType<E> of(List<Function<Random, E>> definition){
		return new RNGType<>(definition);
	}
	
	private record Chance<E>(Function<Random, E> gen, float chance){ }
	
	private final Map<Class<? extends E>, Function<Random, E>> universe;
	
	private final List<Function<Random, E>> randomPick;
	private final List<Chance<E>>           chances = new ArrayList<>();
	private       float                     totalChance;
	
	private RNGType(List<Function<Random, E>> definition){
		if(definition.isEmpty()) throw new IllegalArgumentException("Definitions required");
		
		var rand = new Random(123);
		universe = definition.stream()
		                     .collect(toUnmodifiableMap(
			                     e -> (Class<? extends E>)e.apply(rand).getClass(),
			                     Function.identity()
		                     ));
		
		randomPick = new ArrayList<>(universe.values());
	}
	
	@Override
	public E apply(Random random){
		return getFn(random).apply(random);
	}
	
	private Function<Random, E> getFn(Random random){
		for(Chance(var val, var chance) : chances){
			if(random.nextFloat()<=chance){
				return val;
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
			var totalChance = 0;
			for(var i : chances){
				totalChance += i.chance;
			}
			this.totalChance = totalChance;
		}
		return this;
	}
	
	public <Typ> Function<Random, Typ> map(BiFunction<E, Random, Typ> mapper){
		return random -> {
			var e = RNGType.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
