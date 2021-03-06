package com.lapissea.cfs.io.bit;

import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.UtilL;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.IntFunction;

import static com.lapissea.cfs.GlobalConfig.*;

public final class EnumUniverse<T extends Enum<T>> extends AbstractList<T>{
	
	private static final Map<Class<? extends Enum>, EnumUniverse<?>> CACHE=new HashMap<>();
	
	private interface UGet{
		<E extends Enum<E>> E[] get(Class<E> type);
	}
	
	private static final UGet UNSAFE_GETTER;
	
	static{
		Unsafe us  =null;
		long   uOff=-1;
		
		try{
			Field f=Unsafe.class.getDeclaredField("theUnsafe");
			f.setAccessible(true);
			us=(Unsafe)f.get(null);
		}catch(Throwable ignored){ }
		
		if(us!=null){
			try{
				uOff=us.objectFieldOffset(EnumSet.class.getDeclaredField("universe"));
			}catch(Throwable e){
				us=null;
			}
		}
		if(us==null) UNSAFE_GETTER=null;
		else{
			long   universeOffset=uOff;
			Unsafe unsafe        =us;
			
			UGet uget=new UGet(){
				@Override
				public <E extends Enum<E>> E[] get(Class<E> type){
					try{
						@SuppressWarnings("unchecked")
						E[] universe=(E[])unsafe.getObject(EnumSet.noneOf(type), universeOffset);
						if(DEBUG_VALIDATION){
							if(!Arrays.equals(universe, getUniverseSafe(type))){
								return null;
							}
						}
						return universe;
					}catch(Throwable e){
						return null;
					}
				}
			};
			
			enum DryRun{FOO, BAR}
			if(uget.get(DryRun.class)==null) uget=null;
			
			UNSAFE_GETTER=uget;
		}
	}
	
	private static <T extends Enum<T>> T[] getUniverseUnsafe(Class<T> type){ return UNSAFE_GETTER==null?null:UNSAFE_GETTER.get(type); }
	
	private static <T extends Enum<T>> T[] getUniverseSafe(Class<T> type)  { return type.getEnumConstants(); }
	
	private static <T extends Enum<T>> T[] getUniverse(Class<T> type){
		T[] universe=getUniverseUnsafe(type);
		if(universe==null) universe=getUniverseSafe(type);
		return universe;
	}
	
	private static void ensureEnum(Class<?> type){
		if(!type.isEnum()) throw new IllegalArgumentException(type.getName()+" not an Enum");
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumUniverse<T> getUnknown(Class<?> type){
		ensureEnum(type);
		return get((Class<T>)type);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumUniverse<T> get(Class<T> type){
		EnumUniverse<T> flags=(EnumUniverse<T>)CACHE.get(type);
		if(flags==null){
			synchronized(CACHE){
				ensureEnum(type);
				flags=(EnumUniverse<T>)CACHE.computeIfAbsent(type, EnumUniverse::new);
			}
		}
		return flags;
	}
	
	public final  Class<T> type;
	private final T[]      universe;
	public final  int      bitSize;
	public final  int      nullableBitSize;
	
	
	private EnumUniverse(Class<T> type){
		this.type=type;
		universe=getUniverse(type);
		
		bitSize=calcBits(size());
		nullableBitSize=calcBits(size()+1);
	}
	
	private int calcBits(int size){
		return Math.max(1, (int)Math.ceil(Math.log(size)/Math.log(2)));
	}
	
	public T read(BitReader source) throws IOException{ return read(source, false); }
	public T read(BitReader source, boolean nullable) throws IOException{
		int ordinal=(int)source.readBits(getBitSize(nullable));
		if(nullable){
			if(ordinal==0) return null;
			return get(ordinal-1);
		}
		return get(ordinal);
	}
	
	public void write(T source, BitWriter dest) throws IOException{ write(source, dest, false); }
	public void write(T source, BitWriter dest, boolean nullable) throws IOException{
		int index;
		if(nullable){
			index=source==null?0:source.ordinal()+1;
		}else{
			Objects.requireNonNull(source);
			index=source.ordinal();
		}
		dest.writeBits(index, getBitSize(nullable));
	}
	
	public NumberSize numSize(boolean nullable){
		return NumberSize.byBits(getBitSize(nullable));
	}
	
	public int getBitSize(boolean nullable){
		return nullable?nullableBitSize:bitSize;
	}
	
	@Override
	public int size(){
		return universe.length;
	}
	
	@Override
	public T get(int index){
		return universe[index];
	}
	
	@Override
	public Iterator<T> iterator(){
		return new Iterator<>(){
			int cursor=0;
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
		return o==null||o.getClass()!=type?-1:indexOf((T)o);
	}
	public int indexOf(T o){
		return o==null?-1:o.ordinal();
	}
	
	@Override
	public int lastIndexOf(Object o){
		return indexOf(o);
	}
	
	@Override
	public <T1> T1[] toArray(IntFunction<T1[]> generator){
		T1[] arr=generator.apply(size());
		System.arraycopy(universe, 0, (T1[])arr, 0, size());
		return arr;
	}
	
	@Override
	public Object[] toArray(){
		return universe.clone();
	}
	
	@Override
	public <T1> T1[] toArray(T1[] a){
		T1[] arr=a.length==size()?a:UtilL.array(a, size());
		System.arraycopy(universe, 0, (T1[])arr, 0, size());
		return arr;
	}
	
	@Override
	public ListIterator<T> listIterator(int index){
		return new ListIterator<>(){
			int cursor=index;
			
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
				return cursor-1;
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
}
