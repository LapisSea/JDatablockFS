package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * This class holds the necessary information to request the struct compiler
 * to create an additional field that is not explicitly defined by an {@link IOInstance} type.
 */
public final class VirtualFieldDefinition<IO extends IOInstance<IO>, T>{
	
	public interface GetterFilter<IO extends IOInstance<IO>, T>{
		interface I<IO extends IOInstance<IO>> extends GetterFilter<IO, Integer>{
			int filterPrimitive(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, int value);
			@Override
			@Deprecated
			default Integer filter(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, Integer value){
				return filterPrimitive(ioPool, instance, dependencies, value);
			}
		}
		
		interface L<IO extends IOInstance<IO>> extends GetterFilter<IO, Long>{
			long filterPrimitive(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, long value);
			@Override
			@Deprecated
			default Long filter(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, Long value){
				return filterPrimitive(ioPool, instance, dependencies, value);
			}
		}
		
		T filter(VarPool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, T value);
	}
	
	public final StoragePool         storagePool;
	public final String              name;
	public final Type                type;
	public final GetterFilter<IO, T> getFilter;
	public final GetAnnotation       annotations;
	
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
		this.storagePool = storagePool;
		this.name = name;
		this.type = type;
		this.getFilter = getFilter;
		
		if(annotations.stream().noneMatch(an -> an instanceof IOValue)){
			annotations = Stream.concat(annotations.stream(), Stream.of(IOFieldTools.makeAnnotation(IOValue.class))).toList();
		}
		this.annotations = GetAnnotation.from(annotations);
	}
	
	@Override
	public String toString(){
		return name + ": " + Utils.typeToHuman(type, false);
	}
}
