package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.UtilL;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public final class Flags<E extends Enum<E> & VUtils.FlagSetValue> extends AbstractSet<E>{
	
	public static <E extends Enum<E> & VUtils.FlagSetValue> Flags<E> of(){
		return new Flags<>(null, 0);
	}
	public static <E extends Enum<E> & VUtils.FlagSetValue> Flags<E> of(E value){
		return new Flags<>(value.getDeclaringClass(), value.bit());
	}
	@SafeVarargs
	public static <E extends Enum<E> & VUtils.FlagSetValue> Flags<E> of(E... values){
		return of(Arrays.asList(values));
	}
	public static <E extends Enum<E> & VUtils.FlagSetValue> Flags<E> of(Collection<E> values){
		Class<E> ec = null;
		int      v  = 0;
		for(E e : values){
			v |= e.bit();
			if(ec == null) ec = e.getDeclaringClass();
		}
		return new Flags<>(ec, v);
	}
	
	public final Class<E> enumClass;
	public final int      value;
	
	private List<E> set;
	
	public Flags(Class<E> enumClass, int value){
		this.enumClass = enumClass;
		this.value = value;
	}
	
	private List<E> values(){
		if(set != null) return set;
		if(enumClass == null || value == 0) return set = List.of();
		return set = Iters.from(enumClass).filter(e -> UtilL.checkFlag(value, e.bit())).toList();
	}
	
	@Override
	public Iterator<E> iterator(){ return values().iterator(); }
	
	@Override
	public int size(){ return values().size(); }
	
	@Override
	public String toString(){
		if(value == 0){
			if(enumClass == null) return "EMPTY";
			return enumClass.getSimpleName() + "/EMPTY";
		}
		if(enumClass == null) return "Flags{" + value + "}";
		return values().toString();
	}
}
