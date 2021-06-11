package com.lapissea.cfs.type.field;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.lang.reflect.Type;
import java.util.List;

public final class VirtualFieldDefinition<IO extends IOInstance<IO>, T>{
	
	public interface GetterFilter<IO extends IOInstance<IO>, T>{
		T filter(IO instance, List<IFieldAccessor<IO>> dependencies, T value);
	}
	
	private final boolean             stored;
	private final String              name;
	private final Type                type;
	private final GetterFilter<IO, T> getFilter;
	
	public VirtualFieldDefinition(boolean stored, String name, Type type){
		this(stored, name, type, (___, __, val)->val);
	}
	
	public VirtualFieldDefinition(boolean stored, String name, Type type, GetterFilter<IO, T> getFilter){
		this.stored=stored;
		this.name=name;
		this.type=type;
		this.getFilter=getFilter;
	}
	
	public String getName(){ return name; }
	public Type getType()  { return type; }
	
	public GetterFilter<IO, T> getGetFilter(){
		return getFilter;
	}
	
	public boolean isStored(){
		return stored;
	}
}
