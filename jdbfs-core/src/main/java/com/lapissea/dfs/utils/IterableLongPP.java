package com.lapissea.dfs.utils;

import java.util.Optional;
import java.util.OptionalLong;

public interface IterableLongPP extends IterablePP<Long>{
	
	default long sum(){
		long sum  = 0;
		var  iter = iterator();
		while(iter.hasNext()){
			sum += iter.nextLong();
		}
		return sum;
	}
	
	default OptionalLong min(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		long res = Long.MAX_VALUE;
		while(iter.hasNext()){
			res = Math.min(res, iter.nextLong());
		}
		return OptionalLong.of(res);
	}
	default OptionalLong max(){
		var iter = iterator();
		if(!iter.hasNext()) return OptionalLong.empty();
		long res = Long.MIN_VALUE;
		while(iter.hasNext()){
			res = Math.max(res, iter.nextLong());
		}
		return OptionalLong.of(res);
	}
	
	record Bounds(long min, long max){ }
	default Optional<Bounds> bounds(){
		var iter = iterator();
		if(!iter.hasNext()) return Optional.empty();
		long max = Long.MIN_VALUE, min = Long.MAX_VALUE;
		while(iter.hasNext()){
			var val = iter.nextLong();
			max = Math.max(max, val);
			min = Math.min(min, val);
		}
		return Optional.of(new Bounds(min, max));
	}
	
	default long[] collectToArray(){
		var res  = new long[count()];
		var iter = iterator();
		for(int i = 0; i<res.length; i++){
			res[i] = iter.nextLong();
		}
		return res;
	}
	
	@Override
	default int count(){
		int count = 0;
		var iter  = iterator();
		while(iter.hasNext()){
			var ignore = iter.nextLong();
			count++;
		}
		return count;
	}
	@Override
	LongIterator iterator();
}
