package com.lapissea.cfs.type.field;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.exceptions.FieldIsNullException;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IONullability;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.LongStream;

import static com.lapissea.cfs.type.WordSpace.BIT;
import static com.lapissea.cfs.type.WordSpace.BYTE;

public sealed interface SizeDescriptor<Inst extends IOInstance<Inst>> extends BasicSizeDescriptor<Inst, VarPool<Inst>>{
	
	interface Sizer<T extends IOInstance<T>>{
		long calc(VarPool<T> ioPool, DataProvider prov, T value);
	}
	
	@SuppressWarnings("unchecked")
	final class Fixed<T extends IOInstance<T>> implements IFixed<T, VarPool<T>>, SizeDescriptor<T>{
		
		private static final Fixed<?>[] BIT_CACHE  = LongStream.range(0, 9).mapToObj(i -> new Fixed<>(BIT, i)).toArray(Fixed<?>[]::new);
		private static final Fixed<?>[] BYTE_CACHE = LongStream.range(0, 17).mapToObj(i -> new Fixed<>(BYTE, i)).toArray(Fixed<?>[]::new);
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(BasicSizeDescriptor<?, ?> size){
			if(!size.hasFixed()) throw new IllegalArgumentException("Can not create fixed size from a non fixed descriptor " + size);
			if(size instanceof Fixed) return (Fixed<T>)size;
			return of(size.getWordSpace(), size.getFixed().orElseThrow());
		}
		
		private static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> ofByte(long bytes){
			return bytes>=BYTE_CACHE.length? new Fixed<>(BYTE, bytes) : (Fixed<T>)BYTE_CACHE[(int)bytes];
		}
		private static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> ofBit(long bytes){
			return bytes>=BIT_CACHE.length? new Fixed<>(BIT, bytes) : (Fixed<T>)BIT_CACHE[(int)bytes];
		}
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> empty(){
			return (Fixed<T>)BYTE_CACHE[0];
		}
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(long bytes){
			return ofByte(bytes);
		}
		
		public static <T extends IOInstance<T>> SizeDescriptor.Fixed<T> of(WordSpace wordSpace, long size){
			return switch(wordSpace){
				case BIT -> ofBit(size);
				case BYTE -> ofByte(size);
			};
		}
		
		private final WordSpace wordSpace;
		private final long      size;
		
		private Fixed(WordSpace wordSpace, long size){
			this.wordSpace = wordSpace;
			this.size = size;
		}
		
		@Override
		public <T1 extends IOInstance<T1>> Fixed<T1> map(Function<T1, T> mapping){
			return (Fixed<T1>)this;
		}
		
		@Override
		public WordSpace getWordSpace(){ return wordSpace; }
		
		@Override
		public long get(){ return size; }
		
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
	
	abstract sealed class Unknown<Inst extends IOInstance<Inst>> implements SizeDescriptor<Inst>{
		
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(long min, OptionalLong max, SizeDescriptor.Sizer<Inst> unknownSize){ return of(BYTE, min, max, unknownSize); }
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(WordSpace wordSpace, long min, OptionalLong max, SizeDescriptor.Sizer<Inst> unknownSize){
			return new UnknownLambda<>(wordSpace, min, max, unknownSize);
		}
		
		public static <Inst extends IOInstance<Inst>> SizeDescriptor<Inst> of(NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){
			return new UnknownNum<>(BYTE, min, max, accessor);
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
		
		protected boolean equalsVals(SizeDescriptor.Unknown<?> that){
			return min == that.min &&
			       wordSpace == that.wordSpace &&
			       max.equals(that.max);
		}
	}
	
	final class UnknownLambda<Inst extends IOInstance<Inst>> extends Unknown<Inst>{
		
		
		private final SizeDescriptor.Sizer<Inst> unknownSize;
		
		public UnknownLambda(WordSpace wordSpace, long min, OptionalLong max, SizeDescriptor.Sizer<Inst> unknownSize){
			super(wordSpace, min, max);
			this.unknownSize = unknownSize;
		}
		
		@Override
		public <T extends IOInstance<T>> UnknownLambda<T> map(Function<T, Inst> mapping){
			var unk = unknownSize;
			return new UnknownLambda<>(getWordSpace(), getMin(), getMax(), (ioPool, prov, tInst) -> unk.calc(null, prov, mapping.apply(tInst)));//TODO: uuuuuh ioPool null?
		}
		
		@Override
		public long calcUnknown(VarPool<Inst> ioPool, DataProvider provider, Inst instance, WordSpace wordSpace){
			var unmapped = unknownSize.calc(ioPool, provider, instance);
			return mapSize(wordSpace, unmapped);
		}
		
		@Override
		public boolean equals(Object o){
			return this == o ||
			       o instanceof UnknownLambda<?> that &&
			       equalsVals(that) &&
			       unknownSize == that.unknownSize;
		}
	}
	
	final class UnknownNum<Inst extends IOInstance<Inst>> extends Unknown<Inst>{
		
		private final FieldAccessor<Inst> accessor;
		
		public UnknownNum(NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){ this(BYTE, min, max, accessor); }
		public UnknownNum(WordSpace wordSpace, NumberSize min, Optional<NumberSize> max, FieldAccessor<Inst> accessor){
			super(wordSpace, min.bytes, max.map(n -> OptionalLong.of(n.bytes)).orElse(OptionalLong.empty()));
			if(accessor.getType() != NumberSize.class){
				throw new IllegalArgumentException(accessor + " is not of type " + NumberSize.class.getName());
			}
			if(IOFieldTools.getNullability(accessor) == IONullability.Mode.NULLABLE){
				throw new IllegalArgumentException(accessor + " is nullable");
			}
			this.accessor = accessor;
		}
		
		@Override
		public <T extends IOInstance<T>> UnknownLambda<T> map(Function<T, Inst> mapping){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public long calcUnknown(VarPool<Inst> ioPool, DataProvider provider, Inst instance, WordSpace wordSpace){
			var num = (NumberSize)accessor.get(ioPool, instance);
			if(num == null){
				throw new FieldIsNullException(null, () -> accessor + " value is null");
			}
			return switch(wordSpace){
				case BYTE -> num.bytes;
				case BIT -> num.bits();
			};
		}
		
		
		public FieldAccessor<Inst> getAccessor(){
			return accessor;
		}
		
		@Override
		public boolean equals(Object o){
			return this == o ||
			       o instanceof UnknownNum<?> that &&
			       equalsVals(that) &&
			       accessor == that.accessor;
		}
		@Override
		public String toString(){
			return "NS(" + accessor.getName() + "): " + BasicSizeDescriptor.toString(this);
		}
	}
	
	<T extends IOInstance<T>> SizeDescriptor<T> map(Function<T, Inst> mapping);
	
}
