package com.lapissea.dfs.io.bit;

import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Spliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.stream.Stream;

public final class EnumUniverse<T extends Enum<T>> extends AbstractList<T> implements IterablePP.SizedPP<T>{
	
	private static final Map<Class<? extends Enum>, EnumUniverse<?>> CACHE = new ConcurrentHashMap<>();
	
	private static void ensureEnum(Class<?> type){
		if(!type.isEnum()) throw new IllegalArgumentException(type.getName() + " not an Enum");
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumUniverse<T> ofUnknown(Class<?> type){
		ensureEnum(type);
		return of((Class<T>)type);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumUniverse<T> of(Class<T> type){
		var flags = (EnumUniverse<T>)CACHE.get(type);
		if(flags != null) return flags;
		
		return init(type);
	}
	
	private static <T extends Enum<T>> EnumUniverse<T> init(Class<T> type){
		ensureEnum(type);
		var newFlags = new EnumUniverse<>(type);
		CACHE.put(type, newFlags);
		return newFlags;
	}
	
	public final  Class<T> type;
	private final T[]      universe;
	public final  int      bitSize;
	public final  int      nullableBitSize;
	
	private final NumberSize numberSize;
	private final NumberSize nullableNumberSize;
	
	private EnumUniverse(Class<T> type){
		this.type = type;
		universe = type.getEnumConstants();
		
		bitSize = calcBits(size());
		nullableBitSize = calcBits(size() + 1);
		
		numberSize = NumberSize.byBits(bitSize);
		nullableNumberSize = NumberSize.byBits(nullableBitSize);
	}
	
	private int calcBits(int size){
		return Math.max(1, (int)Math.ceil(Math.log(size)/Math.log(2)));
	}
	
	public void readSkip(BitReader source) throws IOException{ readSkip(source, false); }
	public void readSkip(BitReader source, boolean nullable) throws IOException{
		source.skip(getBitSize(nullable));
	}
	
	public T read(BitReader source) throws IOException{
		return get((int)source.readBits(bitSize));
	}
	public T read(BitReader source, boolean nullable) throws IOException{
		if(!nullable) return read(source);
		
		int ordinal = (int)source.readBits(nullableBitSize);
		if(ordinal == 0) return null;
		return get(ordinal - 1);
	}
	
	public void write(T source, BitWriter<?> dest) throws IOException{
		dest.writeBits(source.ordinal(), bitSize);
	}
	
	public void write(T source, BitWriter<?> dest, boolean nullable) throws IOException{
		if(!nullable){
			Objects.requireNonNull(source);
			write(source, dest);
			return;
		}
		dest.writeBits(source == null? 0 : source.ordinal() + 1, nullableBitSize);
	}
	
	public void write(List<T> enums, BitWriter<?> dest) throws IOException{
		int len = enums.size();
		if(len == 0 || bitSize == 0) return;
		
		int maxBatch = 63/bitSize;
		for(int start = 0; start<len; start += maxBatch){
			var batchSize = Math.min(len - start, maxBatch);
			
			long batch = 0;
			for(int i = 0; i<batchSize; i++){
				batch |= ((long)enums.get(start + i).ordinal())<<(i*bitSize);
			}
			dest.writeBits(batch, batchSize*bitSize);
		}
	}
	
	@SuppressWarnings("unchecked")
	public T[] read(int len, BitReader src) throws IOException{
		var arr = (T[])Array.newInstance(type, len);
		if(bitSize == 0 || len == 0) return arr;
		
		var mask     = BitUtils.makeMask(bitSize);
		int maxBatch = 63/bitSize;
		for(int start = 0; start<len; start += maxBatch){
			var batchSize = Math.min(len - start, maxBatch);
			
			long batch = src.readBits(batchSize*bitSize);
			
			for(int i = 0; i<batchSize; i++){
				arr[i + start] = get((int)((batch >>> (i*bitSize))&mask));
			}
		}
		
		return arr;
	}
	
	public NumberSize numSize(boolean nullable){
		return nullable? nullableNumberSize : numberSize;
	}
	
	public int getBitSize(boolean nullable){
		return nullable? nullableBitSize : bitSize;
	}
	
	@Override
	public int size(){
		return universe.length;
	}
	
	@Override
	@NotNull
	public T get(int index){
		return universe[index];
	}
	
	@Override
	public Iterator<T> iterator(){
		return new Iterator<>(){
			int cursor = 0;
			@Override
			public boolean hasNext(){
				return cursor<size();
			}
			@Override
			public T next(){
				return get(cursor++);
			}
		};
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public int indexOf(Object o){
		return o == null || o.getClass() != type? -1 : indexOf((T)o);
	}
	public int indexOf(T o){
		return o == null? -1 : o.ordinal();
	}
	
	@Override
	public int lastIndexOf(Object o){
		return indexOf(o);
	}
	
	@Override
	public <T1> T1[] toArray(IntFunction<T1[]> generator){
		T1[] arr = generator.apply(size());
		System.arraycopy(universe, 0, (T1[])arr, 0, size());
		return arr;
	}
	
	@Override
	public Object[] toArray(){
		return universe.clone();
	}
	
	@Override
	public <T1> T1[] toArray(T1[] a){
		T1[] arr = a.length == size()? a : UtilL.array(a, size());
		System.arraycopy(universe, 0, (T1[])arr, 0, size());
		return arr;
	}
	
	@Override
	public ListIterator<T> listIterator(int index){
		return new ListIterator<>(){
			int cursor = index;
			
			@Override
			public boolean hasNext(){
				return cursor<size();
			}
			@Override
			public T next(){
				return get(cursor++);
			}
			@Override
			public boolean hasPrevious(){
				return cursor>0;
			}
			@Override
			public T previous(){
				return get(cursor--);
			}
			@Override
			public int nextIndex(){
				return cursor;
			}
			@Override
			public int previousIndex(){
				return cursor - 1;
			}
			@Override
			public void remove(){
				throw new UnsupportedOperationException();
			}
			@Override
			public void set(T t){
				throw new UnsupportedOperationException();
			}
			@Override
			public void add(T t){
				throw new UnsupportedOperationException();
			}
		};
	}
	
	@Override
	public Spliterator<T> spliterator(){
		return Arrays.spliterator(universe);
	}
	@Override
	public T getFirst(){
		return universe[0];
	}
	@Override
	public Stream<T> stream(){
		return super.stream();
	}
	
	@Override
	public OptionalInt calculateSize(){
		return OptionalInt.of(size());
	}
}
