package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.OptionalLong;

public interface SizeDescriptor<T>{
	
	final class Fixed<T> implements SizeDescriptor<T>{
		
		private static final SizeDescriptor<?> SINGLE_BIT=new Fixed<>(WordSpace.BIT, 1);
		public static <T> SizeDescriptor<T> singleBit(){
			return (SizeDescriptor<T>)SINGLE_BIT;
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
		public long variable(T instance){ return size; }
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
	
	abstract class Unknown<T> implements SizeDescriptor<T>{
		
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
	
	default long variableBytes(T instance){
		var size=variable(instance);
		return switch(getWordSpace()){
			case BIT -> Utils.bitToByte(size);
			case BYTE -> size;
		};
	}
	
	default long requireFixed(){
		return getFixed().orElseThrow();
	}
	default long requireMax(){
		return getMax().orElseThrow();
	}
	
	
	WordSpace getWordSpace();
	
	long variable(T instance);
	OptionalLong getFixed();
	
	OptionalLong getMax();
	long getMin();
}
