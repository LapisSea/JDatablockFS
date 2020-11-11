package com.lapissea.cfs.io.bit;

import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

import static com.lapissea.cfs.GlobalConfig.*;

public final class EnumFlag<T extends Enum<T>> extends AbstractList<T>{
	
	private static final Map<Class<? extends Enum>, EnumFlag<?>> CACHE=new HashMap<>();
	
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
	public static <T extends Enum<T>> EnumFlag<T> getUnknown(Class<?> type){
		ensureEnum(type);
		return get((Class<T>)type);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumFlag<T> get(Class<T> type){
		EnumFlag<T> flags=(EnumFlag<T>)CACHE.get(type);
		if(flags==null){
			synchronized(CACHE){
				ensureEnum(type);
				flags=(EnumFlag<T>)CACHE.computeIfAbsent(type, EnumFlag::new);
			}
		}
		return flags;
	}
	
	public final  Class<T> type;
	private final T[]      universe;
	public final  int      bitSize;
	
	
	private EnumFlag(Class<T> type){
		this.type=type;
		universe=getUniverse(type);
		bitSize=Math.max(1, (int)Math.ceil(Math.log(size())/Math.log(2)));
	}
	
	public T read(BitReader source) throws IOException{
		int ordinal=(int)source.readBits(bitSize);
		return get(ordinal);
	}
	
	public void write(T source, BitWriter dest) throws IOException{
		dest.writeBits(source.ordinal(), bitSize);
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
	public Spliterator<T> spliterator(){
		return Arrays.spliterator(universe);
	}
}
