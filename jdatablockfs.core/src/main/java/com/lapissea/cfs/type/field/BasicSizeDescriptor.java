package com.lapissea.cfs.type.field;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.stream.LongStream;

import static com.lapissea.cfs.type.WordSpace.BIT;
import static com.lapissea.cfs.type.WordSpace.BYTE;

public interface BasicSizeDescriptor<T, PoolType>{
	
	interface Sizer<T, PoolType>{
		long calc(PoolType pool, DataProvider prov, T value);
	}
	
	static String toString(BasicSizeDescriptor<?, ?> size){
		var fixed = size.getFixed();
		var siz   = size.getWordSpace();
		if(fixed.isPresent()){
			var fixedVal = fixed.getAsLong();
			return fixedVal + " " + TextUtil.plural(siz.friendlyName, (int)fixedVal);
		}
		
		var min = size.getMin();
		var max = size.getMax();
		
		StringBuilder sb = new StringBuilder();
		if(min>0 || max.isPresent()){
			sb.append(min);
			if(max.isPresent()) sb.append('-').append(max.getAsLong());
			else sb.append("<?");
			sb.append(' ');
		}else{
			sb.append("? ");
		}
		sb.append(TextUtil.plural(siz.friendlyName));
		return sb.toString();
	}
	
	interface IFixed<T, PoolType> extends BasicSizeDescriptor<T, PoolType>{
		
		@SuppressWarnings("unchecked")
		final class Basic<T, PoolType> implements IFixed<T, PoolType>{
			
			private static final Basic<?, ?>[] BIT_CACHE  = LongStream.range(0, 9).mapToObj(i -> new Basic<>(BIT, i)).toArray(Basic<?, ?>[]::new);
			private static final Basic<?, ?>[] BYTE_CACHE = LongStream.range(0, 17).mapToObj(i -> new Basic<>(BYTE, i)).toArray(Basic<?, ?>[]::new);
			
			private static <T, PoolType> Basic<T, PoolType> ofByte(long bytes){
				return bytes>=BYTE_CACHE.length? new Basic<>(BYTE, bytes) : (Basic<T, PoolType>)BYTE_CACHE[(int)bytes];
			}
			private static <T, PoolType> Basic<T, PoolType> ofBit(long bytes){
				return bytes>=BIT_CACHE.length? new Basic<>(BIT, bytes) : (Basic<T, PoolType>)BIT_CACHE[(int)bytes];
			}
			
			public static <T, PoolType> Basic<T, PoolType> of(long bytes){
				return ofByte(bytes);
			}
			
			public static <T, PoolType> Basic<T, PoolType> of(WordSpace wordSpace, long size){
				return switch(wordSpace){
					case BIT -> ofBit(size);
					case BYTE -> ofByte(size);
				};
			}
			
			private final long      size;
			private final WordSpace wordSpace;
			
			private Basic(WordSpace wordSpace, long size){
				this.size = size;
				this.wordSpace = wordSpace;
			}
			
			@Override
			public long get(){
				return size;
			}
			
			@Override
			public WordSpace getWordSpace(){
				return wordSpace;
			}
			
			@Override
			public String toString(){
				return BasicSizeDescriptor.toString(this);
			}
			
			@Override
			public boolean equals(Object o){
				return this == o ||
				       o instanceof BasicSizeDescriptor<?, ?> that &&
				       that.hasFixed() &&
				       this.size == that.getFixed().orElseThrow() &&
				       this.wordSpace == that.getWordSpace();
			}
			
			@Override
			public int hashCode(){
				int result = wordSpace.hashCode();
				result = 31*result + (int)(size^(size >>> 32));
				return result;
			}
		}
		
		default long sizedVal(WordSpace wordSpace){
			return mapSize(wordSpace, get());
		}
		default long get(WordSpace wordSpace){ return sizedVal(wordSpace); }
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
	
	
	abstract sealed class Unknown<Inst, PoolType> implements BasicSizeDescriptor<Inst, PoolType>{
		
		private static final class UnknownLambda<Inst, PoolType> extends BasicSizeDescriptor.Unknown<Inst, PoolType>{
			
			private final BasicSizeDescriptor.Sizer<Inst, PoolType> unknownSize;
			
			public UnknownLambda(WordSpace wordSpace, long min, OptionalLong max, BasicSizeDescriptor.Sizer<Inst, PoolType> unknownSize){
				super(wordSpace, min, max);
				this.unknownSize = unknownSize;
			}
			
			@Override
			public long calcUnknown(PoolType ioPool, DataProvider provider, Inst instance, WordSpace wordSpace){
				var unmapped = unknownSize.calc(ioPool, provider, instance);
				return mapSize(wordSpace, unmapped);
			}
			
			@Override
			public boolean equals(Object o){
				return this == o ||
				       o instanceof UnknownLambda<?, ?> that &&
				       equalsVals(that) &&
				       unknownSize == that.unknownSize;
			}
		}
		
		public static <Inst, PoolType> BasicSizeDescriptor<Inst, PoolType> of(long min, OptionalLong max, Sizer<Inst, PoolType> unknownSize){
			return of(BYTE, min, max, unknownSize);
		}
		public static <Inst, PoolType> BasicSizeDescriptor<Inst, PoolType> of(WordSpace wordSpace, long min, OptionalLong max, Sizer<Inst, PoolType> unknownSize){
			return new UnknownLambda<>(wordSpace, min, max, unknownSize);
		}
		
		private final WordSpace    wordSpace;
		private final long         min;
		private final OptionalLong max;
		
		public Unknown(WordSpace wordSpace, long min, OptionalLong max){
			this.wordSpace = wordSpace;
			this.min = min;
			this.max = Objects.requireNonNull(max);
		}
		
		@Override
		public WordSpace getWordSpace(){ return wordSpace; }
		@Override
		public OptionalLong getFixed(){ return OptionalLong.empty(); }
		@Override
		public OptionalLong getMax(){ return max; }
		@Override
		public long getMin(){ return min; }
		
		@Override
		public String toString(){
			return BasicSizeDescriptor.toString(this);
		}
		
		@Override
		public int hashCode(){
			int result = wordSpace.hashCode();
			result = 31*result + (int)(min^(min >>> 32));
			result = 31*result + max.hashCode();
			return result;
		}
		
		protected boolean equalsVals(Unknown<?, ?> that){
			return min == that.min &&
			       wordSpace == that.wordSpace &&
			       max.equals(that.max);
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
	default OptionalLong getFixed(WordSpace wordSpace){ return mapSize(wordSpace, getFixed()); }
	default boolean hasFixed()                        { return getFixed().isPresent(); }
	
	default long requireFixed(WordSpace wordSpace){
		return getFixed(wordSpace).orElseThrow(() -> new IllegalStateException("Fixed size is required " + this));
	}
	default long requireMax(WordSpace wordSpace){
		return getMax(wordSpace).orElseThrow();
	}
	
	default boolean hasMax(){
		return getMax().isPresent();
	}
	
	default long fixedOrMin(WordSpace wordSpace){
		var fixed = getFixed(wordSpace);
		if(fixed.isPresent()) return fixed.getAsLong();
		return getMin(wordSpace);
	}
	default OptionalLong fixedOrMax(WordSpace wordSpace){
		var fixed = getFixed(wordSpace);
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
		var val = getFixed();
		if(val.isEmpty()){
			var max = getMax();
			if(max.isPresent()){
				var min = getMin();
				val = OptionalLong.of((min + max.getAsLong())/2);
			}
		}
		if(val.isEmpty()){
			var min = getMin();
			var siz = Math.max(min, 32);
			return mapSize(wordSpace, siz);
		}
		
		return mapSize(wordSpace, val.getAsLong());
	}
}
