package com.lapissea.fuzz;

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
	
	private final WeightedPick<E> pick;
	
	private RNGEnum(Class<E> type){
		pick = new WeightedPick<>(getUniverse(type));
	}
	
	@Override
	public E apply(RandomGenerator random){
		return pick.pick(random);
	}
	
	/**
	 * Gives {@code val} an explicit selection chance and removes it from the un-weighted pool.
	 * Un-weighted values share the remaining probability {@code 1 - sum(chances)}; once the explicit
	 * chances sum to at least {@code 1}, the un-weighted pool is never selected.
	 */
	public RNGEnum<E> chanceFor(E val, float chance){
		Objects.requireNonNull(val);
		pick.chanceFor(val, chance);
		return this;
	}
	
	public <Typ> Function<RandomGenerator, Typ> map(BiFunction<E, RandomGenerator, Typ> mapper){
		return random -> mapper.apply(apply(random), random);
	}
}
