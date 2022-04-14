package com.lapissea.cfs.type.field;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.type.WordSpace;

import java.util.OptionalLong;

public interface BasicSizeDescriptor<T, PoolType>{
	
	interface IFixed<T, PoolType> extends BasicSizeDescriptor<T, PoolType>{
		
		class Basic<T, PoolType> implements IFixed<T, PoolType>{
			
			private final long      size;
			private final WordSpace space;
			
			public Basic(long size, WordSpace space){
				this.size=size;
				this.space=space;
			}
			
			@Override
			public long get(){
				return size;
			}
			
			@Override
			public WordSpace getWordSpace(){
				return space;
			}
		}
		
		default long sizedVal(WordSpace wordSpace){
			return mapSize(wordSpace, get());
		}
		default long get(WordSpace wordSpace){return sizedVal(wordSpace);}
		long get();
		
		@Override
		default long calcUnknown(PoolType ioPool, DataProvider provider, T instance, WordSpace wordSpace){
			return get(wordSpace);
		}
		
		@Override
		default long requireFixed(WordSpace wordSpace){
			return get(wordSpace);
		}
		
		@Override
		default long requireMax(WordSpace wordSpace){
			return get(wordSpace);
		}
		@Override
		default long fixedOrMin(WordSpace wordSpace){
			return get(wordSpace);
		}
		@Override
		default OptionalLong fixedOrMax(WordSpace wordSpace){
			return OptionalLong.of(get(wordSpace));
		}
		@Override
		default boolean hasFixed(){
			return true;
		}
		@Override
		default boolean hasMax(){
			return true;
		}
		
		@Override
		default OptionalLong getFixed(){
			return OptionalLong.of(get());
		}
		@Override
		default OptionalLong getMax(){
			return OptionalLong.of(get());
		}
		@Override
		default long getMin(){
			return get();
		}
	}
	
	WordSpace getWordSpace();
	
	long calcUnknown(PoolType ioPool, DataProvider provider, T instance, WordSpace wordSpace);
	
	long getMin();
	default long getMin(WordSpace wordSpace){
		return mapSize(wordSpace, getMin());
	}
	
	OptionalLong getMax();
	default OptionalLong getMax(WordSpace wordSpace){
		return mapSize(wordSpace, getMax());
	}
	
	OptionalLong getFixed();
	default OptionalLong getFixed(WordSpace wordSpace){return mapSize(wordSpace, getFixed());}
	default boolean hasFixed()                        {return getFixed().isPresent();}
	
	default long requireFixed(WordSpace wordSpace){
		return getFixed(wordSpace).orElseThrow(()->new IllegalStateException("Fixed size is required "+this));
	}
	default long requireMax(WordSpace wordSpace){
		return getMax(wordSpace).orElseThrow();
	}
	
	default boolean hasMax(){
		return getMax().isPresent();
	}
	
	default long fixedOrMin(WordSpace wordSpace){
		var fixed=getFixed(wordSpace);
		if(fixed.isPresent()) return fixed.getAsLong();
		return getMin(wordSpace);
	}
	default OptionalLong fixedOrMax(WordSpace wordSpace){
		var fixed=getFixed(wordSpace);
		if(fixed.isPresent()) return fixed;
		return getMax(wordSpace);
	}
	
	default OptionalLong mapSize(WordSpace targetSpace, OptionalLong val){
		if(val.isEmpty()) return val;
		return OptionalLong.of(mapSize(targetSpace, val.getAsLong()));
	}
	
	default long mapSize(WordSpace targetSpace, long val){
		return WordSpace.mapSize(getWordSpace(), targetSpace, val);
	}
	
	default long calcAllocSize(WordSpace wordSpace){
		var val=getFixed();
		if(val.isEmpty()) val=getMax();
		if(val.isEmpty()){
			var min=getMin();
			var siz=Math.max(min, 32);
			return mapSize(wordSpace, siz);
		}
		
		return mapSize(wordSpace, val.getAsLong());
	}
}
