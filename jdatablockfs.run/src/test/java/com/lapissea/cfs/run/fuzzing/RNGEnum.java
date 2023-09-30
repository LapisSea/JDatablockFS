package com.lapissea.cfs.run.fuzzing;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.random.RandomGenerator;

public final class RNGEnum<E extends Enum<E>> implements Function<RandomGenerator, E>{
	
	@SuppressWarnings("rawtypes")
	private static final Map<Class<? extends Enum>, List<?>> ENUM_CACHE = new ConcurrentHashMap<>();
	
	private static <E extends Enum<E>> List<E> getUniverse(Class<E> type){
		@SuppressWarnings("unchecked")
		var flags = (List<E>)ENUM_CACHE.get(type);
		if(flags != null) return flags;
		return makeUniverse(type);
	}
	private static <E extends Enum<E>> List<E> makeUniverse(Class<E> type){
		if(!type.isEnum()) throw new IllegalArgumentException(type.getName() + " not an Enum");
		var uni = List.of(type.getEnumConstants());
		if(uni.isEmpty()) throw new IllegalArgumentException(type.getName() + " has no values");
		ENUM_CACHE.put(type, uni);
		return uni;
	}
	
	public static <E extends Enum<E>> E anyOf(RandomGenerator random, Class<E> type){
		var uni = getUniverse(type);
		return uni.get(random.nextInt(uni.size()));
	}
	
	public static <E extends Enum<E>> RNGEnum<E> of(Class<E> type){
		return new RNGEnum<>(type);
	}
	
	private record Chance<E extends Enum<E>>(E val, float chance){ }
	
	private final List<E>         randomPick;
	private final List<Chance<E>> chances = new ArrayList<>();
	private       float           totalChance;
	
	private RNGEnum(Class<E> type){
		randomPick = new ArrayList<>(getUniverse(type));
	}
	
	@Override
	public E apply(RandomGenerator random){
		for(var ch : chances){
			if(random.nextFloat()<=ch.chance){
				return ch.val;
			}
		}
		
		if(randomPick.isEmpty()){
			int idx = 0;
			for(float r = random.nextFloat()*totalChance; idx<chances.size() - 1; ++idx){
				r -= chances.get(idx).chance;
				if(r<=0.0) break;
			}
			return chances.get(idx).val;
		}
		
		return randomPick.get(random.nextInt(randomPick.size()));
	}
	
	public RNGEnum<E> chanceFor(E val, float chance){
		Objects.requireNonNull(val);
		chances.add(new Chance<>(val, chance));
		randomPick.remove(val);
		
		if(randomPick.isEmpty()){
			var totalChance = 0;
			for(var i : chances){
				totalChance += i.chance;
			}
			this.totalChance = totalChance;
		}
		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> {
			var e = RNGEnum.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
