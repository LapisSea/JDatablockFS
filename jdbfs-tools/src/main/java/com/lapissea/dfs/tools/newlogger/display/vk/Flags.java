package com.lapissea.dfs.tools.newlogger.display.vk;

import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.PPBakedSequence;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.SequencedCollection;

public final class Flags<E extends Enum<E> & VUtils.FlagSetValue> extends AbstractSet<E> implements SequencedCollection<E>, Stringify{
	
	static{
		TextUtil.CUSTOM_TO_STRINGS.register(Flags.class, Flags::toString);
	}
	
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
	
	public final  Class<E> enumClass;
	public final  int      value;
	private final boolean  reversed;
	
	private PPBakedSequence<E> set;
	
	public Flags(Class<E> enumClass, int value){
		this(enumClass, value, false);
	}
	private Flags(Class<E> enumClass, int value, boolean reversed){
		this.enumClass = enumClass;
		this.value = value;
		this.reversed = reversed;
	}
	
	private PPBakedSequence<E> values(){
		if(set != null) return set;
		if(enumClass == null || value == 0) return set = Iters.<E>of().bake();
		var raw = Iters.from(enumClass).filter(e -> UtilL.checkFlag(value, e.bit()));
		if(reversed) raw = raw.reverse();
		return set = raw.bake();
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
	public String toShortString(){
		if(value == 0){
			return "(EMPTY)";
		}
		if(enumClass == null) return "(" + value + ")";
		return values().joinAsStr(", ", "{", "}");
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
	
	@Override
	public Flags<E> reversed(){
		return new Flags<>(enumClass, value, !reversed);
	}
	@Override
	public E getLast(){
		return values().getLast();
	}
	@Override
	public E getFirst(){
		return values().getFirst();
	}
	
	public E asOne(){
		if(size() == 1) return getFirst();
		throw new IllegalStateException("Only one flag is allowed");
	}
	
	public Flags<E> and(E flag){
		var ec = enumClass;
		if(ec == null) ec = flag.getDeclaringClass();
		return new Flags<>(ec, this.value|flag.bit());
	}
}
