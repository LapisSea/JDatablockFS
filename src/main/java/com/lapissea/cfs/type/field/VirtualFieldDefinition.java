package com.lapissea.cfs.type.field;

import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.access.IFieldAccessor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;

public final class VirtualFieldDefinition<IO extends IOInstance<IO>, T>{
	
	public interface GetterFilter<IO extends IOInstance<IO>, T>{
		T filter(IO instance, List<IFieldAccessor<IO>> dependencies, T value);
	}
	
	public enum StoragePool{
		INSTANCE("<<"),
		IO("IO"),
		NONE("x");
		
		public final String shortName;
		StoragePool(String shortName){this.shortName=shortName;}
	}
	
	public final  StoragePool         storagePool;
	private final String              name;
	private final Type                type;
	private final GetterFilter<IO, T> getFilter;
	private final GetAnnotation       annotations;
	
	public VirtualFieldDefinition(StoragePool storagePool, String name, Type type){
		this(storagePool, name, type, null, List.of());
	}
	public VirtualFieldDefinition(StoragePool storagePool, String name, Type type, Collection<Annotation> annotations){
		this(storagePool, name, type, null, annotations);
	}
	
	public VirtualFieldDefinition(StoragePool storagePool, String name, Type type, GetterFilter<IO, T> getFilter){
		this(storagePool, name, type, getFilter, List.of());
	}
	public VirtualFieldDefinition(StoragePool storagePool, String name, Type type, GetterFilter<IO, T> getFilter, Collection<Annotation> annotations){
		this.storagePool=storagePool;
		this.name=name;
		this.type=type;
		this.getFilter=getFilter;
		this.annotations=GetAnnotation.from(annotations);
	}
	
	public String getName()                  { return name; }
	public Type getType()                    { return type; }
	public GetterFilter<IO, T> getGetFilter(){ return getFilter; }
	public GetAnnotation getAnnotations()    { return annotations; }
}