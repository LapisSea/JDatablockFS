package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.PPBakedSequence;
import com.lapissea.util.UtilL;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;

public final class Flags<E extends Enum<E> & VUtils.FlagSetValue> extends AbstractSet<E>{
	
	private static final Flags<?> EMPTY = new Flags<>(null, 0);
	public static <E extends Enum<E> & VUtils.FlagSetValue> Flags<E> of(){
		//noinspection unchecked
		return (Flags<E>)EMPTY;
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
	
	private PPBakedSequence<E> set;
	
	public Flags(Class<E> enumClass, int value){
		this.enumClass = enumClass;
		this.value = value;
	}
	
	private PPBakedSequence<E> values(){
		if(set != null) return set;
		if(enumClass == null || value == 0) return set = Iters.<E>of().bake();
		return set = Iters.from(enumClass).filter(e -> UtilL.checkFlag(value, e.bit())).bake();
	}
	
	@Override
	public Iterator<E> iterator(){ return values().iterator(); }
	
	@Override
	public int size(){ return values().size(); }
	
	@Override
	public String toString(){
		if(value == 0){
			return (enumClass == null? "Flags" : enumClass.getSimpleName()) + "(EMPTY)";
		}
		if(enumClass == null) return "Flags(" + value + ")";
		return values().joinAsStr(", ", enumClass.getSimpleName() + ":{", "}");
	}
	
	@Override
	public boolean contains(Object o){
		return o instanceof VUtils.FlagSetValue e &&
		       UtilL.checkFlag(value, e.bit()) &&
		       (enumClass == null || enumClass.isInstance(o));
	}
	@Override
	public int hashCode(){
		return enumClass.hashCode() + value*31;
	}
	@Override
	public boolean equals(Object o){
		if(o instanceof Flags<?> that){
			return that.enumClass == this.enumClass &&
			       that.value == this.value;
		}
		return super.equals(o);
	}
}
