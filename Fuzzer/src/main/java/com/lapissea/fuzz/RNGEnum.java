package com.lapissea.fuzz;

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
	private       double          totalChance;
	
	private RNGEnum(Class<E> type){
		randomPick = new ArrayList<>(getUniverse(type));
	}
	
	@Override
	public E apply(RandomGenerator random){
		if(!chances.isEmpty()){
			if(randomPick.isEmpty() && totalChance == 0){
				throw new IllegalStateException("At least one choice must have a positive chance");
			}
			double remaining = random.nextFloat()*(randomPick.isEmpty()? totalChance : Math.max(1F, totalChance));
			for(var ch : chances){
				if(remaining<ch.chance) return ch.val;
				remaining -= ch.chance;
			}
		}

		return randomPick.get(random.nextInt(randomPick.size()));
	}
	
	public RNGEnum<E> chanceFor(E val, float chance){
		if(!Float.isFinite(chance) || chance<0){
			throw new IllegalArgumentException("Chance must be finite and non-negative");
		}
		Objects.requireNonNull(val);
		chances.add(new Chance<>(val, chance));
		randomPick.remove(val);
		
		totalChance += chance;

		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> {
			var e = RNGEnum.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
