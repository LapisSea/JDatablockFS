package com.lapissea.cfs.type.field;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.TextUtil;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.LongStream;

import static com.lapissea.cfs.type.WordSpace.BIT;
import static com.lapissea.cfs.type.WordSpace.BYTE;

public sealed interface SizeDescriptor<Inst extends IOInstance<Inst>>{
	
	interface Sizer<T extends IOInstance<T>>{
		long calc(Struct.Pool<T> ioPool, DataProvider prov, T value);
	}
	
	@SuppressWarnings("unchecked")
	final class Fixed<T extends IOInstance<T>> implements SizeDescriptor<T>{
		
		private static final Fixed<?>[] BIT_CACHE =LongStream.range(0, 8).mapToObj(i->new Fixed<>(BIT, i)).toArray(Fixed<?>[]::new);
		private static final Fixed<?>[] BYTE_CACHE=LongStream.range(0, 16).mapToObj(i->new Fixed<>(BYTE, i)).toArray(Fixed<?>[]::new);
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(SizeDescriptor<?> size){
			if(!size.hasFixed()) throw new IllegalArgumentException("Can not create fixed size from a non fixed descriptor "+size);
			if(size instanceof Fixed) return (Fixed<T>)size;
			return of(size.getWordSpace(), size.getFixed().orElseThrow());
		}
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(long bytes){
			if(bytes>=BYTE_CACHE.length){
				return new Fixed<>(BYTE, bytes);
			}
			return (Fixed<T>)BYTE_CACHE[(int)bytes];
		}
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(WordSpace wordSpace, long size){
			var pool=(Fixed<T>[])switch(wordSpace){
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
		
		private Fixed(WordSpace wordSpace, long size){
			this.wordSpace=wordSpace;
			this.size=size;
		}
		
		@Override
		public <T1 extends IOInstance<T1>> Fixed<T1> map(Function<T1, T> mapping){
			return (Fixed<T1>)this;
		}
		
		@Override
		public WordSpace getWordSpace(){return wordSpace;}
		@Override
		public long calcUnknown(Struct.Pool<T> ioPool, DataProvider provider, T instance){
			return size;
			//throw new ShouldNeverHappenError("Do not calculate unknown, use getFixed when it is provided");
		}
		
		private long sizedVal(WordSpace wordSpace){
			return WordSpace.mapSize(this.wordSpace, wordSpace, size);
		}
		
		@Override
		public long requireFixed(WordSpace wordSpace){
			return sizedVal(wordSpace);
		}
		
		@Override
		public long requireMax(WordSpace wordSpace){
			return sizedVal(wordSpace);
		}
		@Override
		public long fixedOrMin(WordSpace wordSpace){
			return sizedVal(wordSpace);
		}
		@Override
		public OptionalLong fixedOrMax(WordSpace wordSpace){
			return OptionalLong.of(sizedVal(wordSpace));
		}
		@Override
		public boolean hasFixed(){
			return true;
		}
		@Override
		public boolean hasMax(){
			return true;
		}
		
		public long get(WordSpace wordSpace){return mapSize(wordSpace, get());}
		public long get()                   {return size;}
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
	
	abstract sealed class Unknown<Inst extends IOInstance<Inst>> implements SizeDescriptor<Inst>{
		
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(long min, OptionalLong max, Sizer<Inst> unknownSize){return of(BYTE, min, max, unknownSize);}
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(WordSpace wordSpace, long min, OptionalLong max, Sizer<Inst> unknownSize){
			return new UnknownLambda<>(wordSpace, min, max, unknownSize);
		}
		
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){
			return new UnknownNum<>(BYTE, min, max, accessor);
		}
		
		
		private final WordSpace    wordSpace;
		private final long         min;
		private final OptionalLong max;
		
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
			}else{
				sb.append("? ");
			}
			sb.append(TextUtil.plural(getWordSpace().friendlyName));
			return sb.append('}').toString();
		}
		
		
		protected boolean equalsVals(Unknown<?> that){
			return min==that.min&&
			       wordSpace==that.wordSpace&&
			       max.equals(that.max);
		}
		
		protected int hashCodeVals(){
			int result=wordSpace.hashCode();
			result=31*result+(int)(min^(min >>> 32));
			result=31*result+max.hashCode();
			return result;
		}
	}
	
	final class UnknownLambda<Inst extends IOInstance<Inst>> extends Unknown<Inst>{
		
		
		private final Sizer<Inst> unknownSize;
		
		public UnknownLambda(WordSpace wordSpace, long min, OptionalLong max, Sizer<Inst> unknownSize){
			super(wordSpace, min, max);
			this.unknownSize=unknownSize;
		}
		
		@Override
		public <T extends IOInstance<T>> UnknownLambda<T> map(Function<T, Inst> mapping){
			var unk=unknownSize;
			return new UnknownLambda<>(getWordSpace(), getMin(), getMax(), (ioPool, prov, tInst)->unk.calc(null, prov, mapping.apply(tInst)));//TODO: uuuuuh ioPool null?
		}
		
		@Override
		public long calcUnknown(Struct.Pool<Inst> ioPool, DataProvider provider, Inst instance){
			return unknownSize.calc(ioPool, provider, instance);
		}
	}
	
	final class UnknownNum<Inst extends IOInstance<Inst>> extends Unknown<Inst>{
		
		private final FieldAccessor<Inst> accessor;
		
		public UnknownNum(NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){this(BYTE, min, max, accessor);}
		public UnknownNum(WordSpace wordSpace, NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){
			super(wordSpace, min.bytes, max.map(n->OptionalLong.of(n.bytes)).orElse(OptionalLong.empty()));
			if(accessor.getType()!=NumberSize.class){
				throw new IllegalArgumentException(accessor+" is not of type "+NumberSize.class.getName());
			}
			this.accessor=accessor;
		}
		
		@Override
		public <T extends IOInstance<T>> UnknownLambda<T> map(Function<T, Inst> mapping){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public long calcUnknown(Struct.Pool<Inst> ioPool, DataProvider provider, Inst instance){
			var num=(NumberSize)accessor.get(ioPool, instance);
			return num.bytes;
		}
		
		
		public FieldAccessor<Inst> getAccessor(){
			return accessor;
		}
		
		@Override
		public boolean equals(Object o){
			return this==o||
			       o instanceof UnknownNum<?> that&&
			       equalsVals(that)&&
			       accessor==that.accessor;
		}
		
		@Override
		public int hashCode(){
			int result=hashCodeVals();
			result=31*result+accessor.hashCode();
			return result;
		}
	}
	
	default long requireFixed(WordSpace wordSpace){
		return getFixed(wordSpace).orElseThrow(()->new IllegalStateException("Fixed size is required "+this));
	}
	default long requireMax(WordSpace wordSpace){
		return getMax(wordSpace).orElseThrow();
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
	
	default boolean hasFixed(){
		return getFixed().isPresent();
	}
	default boolean hasMax(){
		return getMax().isPresent();
	}
	
	<T extends IOInstance<T>> SizeDescriptor<T> map(Function<T, Inst> mapping);
	
	WordSpace getWordSpace();
	
	long calcUnknown(Struct.Pool<Inst> ioPool, DataProvider provider, Inst instance);
	OptionalLong getFixed();
	
	OptionalLong getMax();
	long getMin();
	
	default long getMin(WordSpace wordSpace){
		return mapSize(wordSpace, getMin());
	}
	
	default OptionalLong getMax(WordSpace wordSpace){
		return mapSize(wordSpace, getMax());
	}
	
	default OptionalLong getFixed(WordSpace wordSpace){
		return mapSize(wordSpace, getFixed());
	}
	
	default long calcUnknown(Struct.Pool<Inst> ioPool, DataProvider provider, Inst instance, WordSpace wordSpace){
		var unknown=calcUnknown(ioPool, provider, instance);
		return mapSize(wordSpace, unknown);
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
