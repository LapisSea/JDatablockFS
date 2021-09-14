package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;
import java.util.stream.LongStream;

import static com.lapissea.cfs.type.WordSpace.*;

public sealed interface SizeDescriptor<Inst extends IOInstance<Inst>>{
	
	@SuppressWarnings("unchecked")
	final class Fixed<T extends IOInstance<T>> implements SizeDescriptor<T>{
		
		private static final Fixed<?>[] BIT_CACHE =LongStream.range(0, 8).mapToObj(i->new Fixed<>(BIT, i)).toArray(Fixed<?>[]::new);
		private static final Fixed<?>[] BYTE_CACHE=LongStream.range(0, 16).mapToObj(i->new Fixed<>(BYTE, i)).toArray(Fixed<?>[]::new);
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(long bytes){
			if(bytes>=BYTE_CACHE.length){
				return new Fixed<>(BYTE, bytes);
			}
			return (Fixed<T>)BYTE_CACHE[(int)bytes];
		}
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(WordSpace wordSpace, long size){
			Fixed<T>[] pool=(Fixed<T>[])switch(wordSpace){
				case BIT -> BIT_CACHE;
				case BYTE -> BYTE_CACHE;
			};
			if(size>=pool.length){
				return new Fixed<>(wordSpace, size);
			}
			return pool[(int)size];
		}
		
		
		private final WordSpace wordSpace;
		private final long      size;
		
		public Fixed(long bytes){
			this(BYTE, bytes);
		}
		public Fixed(WordSpace wordSpace, long size){
			this.wordSpace=wordSpace;
			this.size=size;
		}
		
		@Override
		public WordSpace getWordSpace(){return wordSpace;}
		@Override
		public long calcUnknown(T instance){
			return size;
			//throw new ShouldNeverHappenError("Do not calculate unknown, use getFixed when it is provided");
		}
		
		public long get(){return size;}
		@Override
		public OptionalLong getFixed(){return OptionalLong.of(size);}
		@Override
		public OptionalLong getMax(){return OptionalLong.of(size);}
		@Override
		public long getMin(){return size;}
		
		public String toShortString(){
			return "{"+size+" "+TextUtil.plural(getWordSpace().friendlyName, (int)size)+"}";
		}
		@Override
		public String toString(){
			return "Size"+toShortString();
		}
	}
	
	abstract non-sealed class Unknown<Inst extends IOInstance<Inst>> implements SizeDescriptor<Inst>{
		
		private final WordSpace    wordSpace;
		private final long         min;
		private final OptionalLong max;
		
		public Unknown(long min, OptionalLong max){
			this(BYTE, min, max);
		}
		public Unknown(WordSpace wordSpace, long min, OptionalLong max){
			this.wordSpace=wordSpace;
			this.min=min;
			this.max=Objects.requireNonNull(max);
		}
		
		@Override
		public WordSpace getWordSpace(){return wordSpace;}
		@Override
		public OptionalLong getFixed(){return OptionalLong.empty();}
		@Override
		public OptionalLong getMax(){return max;}
		@Override
		public long getMin(){return min;}
		
		@Override
		public String toString(){
			return "Size"+toShortString();
		}
		public String toShortString(){
			StringBuilder sb=new StringBuilder();
			sb.append('{');
			if(min>0||max.isPresent()){
				sb.append(min);
				if(max.isPresent()) sb.append('-').append(max.getAsLong());
				else sb.append("<?");
				sb.append(' ');
			}
			sb.append(TextUtil.plural(getWordSpace().friendlyName));
			return sb.append('}').toString();
		}
	}
	
	static <T extends IOInstance<T>> SizeDescriptor<T> overrideUnknown(SizeDescriptor<?> source, ToLongFunction<T> override){
		if(source.getFixed().isPresent()) return source instanceof Fixed<?> f?(SizeDescriptor<T>)f:new Fixed<>(source.getFixed().getAsLong());
		return new Unknown<>(source.getWordSpace(), source.getMin(), source.getMax()){
			@Override
			public long calcUnknown(T instance){
				return override.applyAsLong(instance);
			}
		};
	}
	
	default OptionalLong toBytes(OptionalLong val){
		if(val.isEmpty()) return val;
		return OptionalLong.of(toBytes(val.getAsLong()));
	}
	default long toBytes(long val){
		return switch(getWordSpace()){
			case BIT -> Utils.bitToByte(val);
			case BYTE -> val;
		};
	}
	
	default long requireFixed(){
		return getFixed().orElseThrow(()->new IllegalStateException("Fixed size is required"));
	}
	default long requireMax(){
		return getMax().orElseThrow();
	}
	
	default SizeDescriptor<Inst> maxAsFixed(){
		if(getFixed().isPresent()) return this;
		return new Fixed<>(getWordSpace(), requireMax());
	}
	
	default long fixedOrMin(){
		var fixed=getFixed();
		if(fixed.isPresent()) return fixed.getAsLong();
		return getMin();
	}
	default OptionalLong fixedOrMax(){
		var fixed=getFixed();
		if(fixed.isPresent()) return fixed;
		return getMax();
	}
	
	default boolean hasFixed(){
		return getFixed().isPresent();
	}
	default boolean hasMax(){
		return getMax().isPresent();
	}
	
	WordSpace getWordSpace();
	
	long calcUnknown(Inst instance);
	OptionalLong getFixed();
	
	OptionalLong getMax();
	long getMin();
}
