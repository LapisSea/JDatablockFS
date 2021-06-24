package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;

public interface SizeDescriptor<Inst extends IOInstance<Inst>>{
	
	final class Fixed<T extends IOInstance<T>> implements SizeDescriptor<T>{
		
		private static final SizeDescriptor<?> SINGLE_BIT=new Fixed<>(WordSpace.BIT, 1);
		private static final SizeDescriptor<?> EMPTY     =new Fixed<>(WordSpace.BYTE, 0);
		
		public static <T extends IOInstance<T>> SizeDescriptor<T> singleBit(){
			return (SizeDescriptor<T>)SINGLE_BIT;
		}
		public static <T extends IOInstance<T>> SizeDescriptor<T> empty(){
			return (SizeDescriptor<T>)EMPTY;
		}
		
		
		private final WordSpace wordSpace;
		private final long      size;
		
		public Fixed(long size){
			this(WordSpace.BYTE, size);
		}
		public Fixed(WordSpace wordSpace, long size){
			this.wordSpace=wordSpace;
			this.size=size;
		}
		
		@Override
		public WordSpace getWordSpace(){ return wordSpace; }
		@Override
		public long calcUnknown(T instance){
			return size;
			//throw new ShouldNeverHappenError("Do not calculate unknown, use getFixed when it is provided");
		}
		@Override
		public OptionalLong getFixed(){ return OptionalLong.of(size); }
		@Override
		public OptionalLong getMax(){ return OptionalLong.of(size); }
		@Override
		public long getMin(){ return size; }
		
		public String toShortString(){
			return "{"+size+" "+TextUtil.plural(getWordSpace().friendlyName, (int)size)+"}";
		}
		@Override
		public String toString(){
			return "Size"+toShortString();
		}
	}
	
	abstract class Unknown<Inst extends IOInstance<Inst>> implements SizeDescriptor<Inst>{
		
		private final WordSpace    wordSpace;
		private final long         min;
		private final OptionalLong max;
		
		public Unknown(long min, OptionalLong max){
			this(WordSpace.BYTE, min, max);
		}
		public Unknown(WordSpace wordSpace, long min, OptionalLong max){
			this.wordSpace=wordSpace;
			this.min=min;
			this.max=Objects.requireNonNull(max);
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
