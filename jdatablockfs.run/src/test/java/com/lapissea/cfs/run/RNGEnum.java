package com.lapissea.cfs.run;

import com.lapissea.cfs.io.bit.EnumUniverse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class RNGEnum<E extends Enum<E>> implements Function<Random, E>{
	
	private static <E extends Enum<E>> EnumUniverse<E> getUniverse(Class<E> type){
		var uni = EnumUniverse.of(type);
		if(uni.isEmpty()) throw new IllegalArgumentException(type.getName() + " has no values");
		return uni;
	}
	
	public static <E extends Enum<E>> E anyOf(Random random, Class<E> type){
		EnumUniverse<E> uni = getUniverse(type);
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
	public E apply(Random random){
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
	
	public <Typ> Function<Random, Typ> map(BiFunction<E, Random, Typ> mapper){
		return random -> {
			var e = RNGEnum.this.apply(random);
			return mapper.apply(e, random);
		};
	}
}
