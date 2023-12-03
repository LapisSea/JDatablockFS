package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class holds the necessary information to request the struct compiler
 * to create an additional field that is not explicitly defined by an {@link IOInstance} type.
 */
public final class VirtualFieldDefinition<IO extends IOInstance<IO>, T>{
	
	public interface GetterFilter<IO extends IOInstance<IO>, T>{
		T filter(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, T value);
	}
	
	public final StoragePool         storagePool;
	public final String              name;
	public final Type                type;
	public final GetterFilter<IO, T> getFilter;
	
	public final Map<Class<? extends Annotation>, ? extends Annotation> annotations;
	
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
		this.storagePool = Objects.requireNonNull(storagePool);
		this.name = Objects.requireNonNull(name);
		this.type = Objects.requireNonNull(type);
		this.getFilter = getFilter;
		
		if(annotations.stream().noneMatch(an -> an instanceof IOValue)){
			annotations = Stream.concat(annotations.stream(), Stream.of(IOFieldTools.makeAnnotation(IOValue.class))).toList();
		}
		this.annotations = annotations.stream().collect(Collectors.toUnmodifiableMap(Annotation::annotationType, a -> a));
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(!(o instanceof VirtualFieldDefinition<?, ?> that)) return false;
		
		if(storagePool != that.storagePool) return false;
		if(!name.equals(that.name)) return false;
		if(!type.equals(that.type)) return false;
		if((getFilter == null) != (that.getFilter == null)) return false;
		return annotations.equals(that.annotations);
	}
	@Override
	public int hashCode(){
		return name.hashCode();
	}
	@Override
	public String toString(){
		return name + ": " + Utils.typeToHuman(type, false);
	}
	
	public String name(){ return name; }
}