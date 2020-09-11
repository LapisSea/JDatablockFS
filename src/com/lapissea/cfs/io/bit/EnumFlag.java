package com.lapissea.cfs.io.bit;

import com.lapissea.util.WeakValueHashMap;

import java.util.Map;

public class EnumFlag<T extends Enum<T>>{
	
	private static final Map<Class<? extends Enum<?>>, EnumFlag<?>> CACHE=new WeakValueHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumFlag<T> getUnknown(Class<?> type){
		if(!type.isEnum()) throw new IllegalArgumentException(type.getName()+" not an Enum");
		
		return get((Class<T>)type);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Enum<T>> EnumFlag<T> get(Class<T> type){
		return (EnumFlag<T>)CACHE.computeIfAbsent(type, e->new EnumFlag<>(type));
	}
	
	public final  Class<T> type;
	private final T[]      consts;
	public final  int      bits;
	
	private EnumFlag(Class<T> type){
		if(!type.isEnum()) throw new IllegalArgumentException(type.getName()+" is not an Enum");
		
		this.type=type;
		
		consts=type.getEnumConstants();
		bits=Math.max(1, (int)Math.ceil(Math.log(consts.length)/Math.log(2)));
	}
	
	public T read(BitReader source){
		int ordinal=source.readBits(bits);
		return getEnumByIndex(ordinal);
	}
	
	public void write(T source, BitWriter dest){
		dest.writeBits(source.ordinal(), bits);
	}
	
	public int getEnumCount(){
		return consts.length;
	}
	
	public T getEnumByIndex(int index){
		return consts[index];
	}
	
}
