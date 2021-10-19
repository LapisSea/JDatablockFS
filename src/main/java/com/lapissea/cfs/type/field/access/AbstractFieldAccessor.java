package com.lapissea.cfs.type.field.access;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.util.NotNull;

import java.util.Objects;

public abstract class AbstractFieldAccessor<CTyp extends IOInstance<CTyp>> implements FieldAccessor<CTyp>{
	
	private final Struct<CTyp> struct;
	private final String       name;
	
	protected AbstractFieldAccessor(Struct<CTyp> struct, String name){
		this.struct=struct;
		this.name=Objects.requireNonNull(name);
	}
	
	@Override
	public final Struct<CTyp> getDeclaringStruct(){
		return struct;
	}
	
	@NotNull
	@Override
	public final String getName(){
		return name;
	}
	
	protected String strName(){return getName();}
	
	@Override
	public String toString(){
		var struct=getDeclaringStruct();
		return (struct==null?"":struct.getType().getSimpleName())+"#"+strName();
	}
	public String toShortString(){
		return strName();
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof FieldAccessor<?> that&&
		       Objects.equals(getDeclaringStruct(), that.getDeclaringStruct())&&
		       getName().equals(that.getName());
	}
	
	@Override
	public int hashCode(){
		var struct=getDeclaringStruct();
		int result=struct==null?0:struct.hashCode();
		result=31*result+getName().hashCode();
		return result;
	}
}
