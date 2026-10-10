package com.lapissea.fuzz;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.random.RandomGenerator;

final class WeightedPick<T>{
	
	private record Chance<T>(T value, float chance){ }
	
	private final List<T>         randomPick;
	private final List<Chance<T>> chances = new ArrayList<>();
	private       double          totalChance;
	
	WeightedPick(Collection<T> universe){
		randomPick = new ArrayList<>(universe);
	}
	
	T pick(RandomGenerator random){
		if(!chances.isEmpty()){
			if(randomPick.isEmpty() && totalChance == 0){
				throw new IllegalStateException("At least one choice must have a positive chance");
			}
			double remaining = random.nextFloat()*(randomPick.isEmpty()? totalChance : Math.max(1F, totalChance));
			for(var ch : chances){
				if(remaining<ch.chance) return ch.value;
				remaining -= ch.chance;
			}
		}
		
		return randomPick.get(random.nextInt(randomPick.size()));
	}
	
	void chanceFor(T value, float chance){
		if(!Float.isFinite(chance) || chance<0){
			throw new IllegalArgumentException("Chance must be finite and non-negative");
		}
		chances.add(new Chance<>(value, chance));
		randomPick.remove(value);
		totalChance += chance;
	}
}
