package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
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
			int filterPrimitive(Struct.Pool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, int value);
			@Override
			@Deprecated
			default Integer filter(Struct.Pool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, Integer value){
				return filterPrimitive(ioPool, instance, dependencies, value);
			}
		}
		
		interface L<IO extends IOInstance<IO>> extends GetterFilter<IO, Long>{
			long filterPrimitive(Struct.Pool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, long value);
			@Override
			@Deprecated
			default Long filter(Struct.Pool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, Long value){
				return filterPrimitive(ioPool, instance, dependencies, value);
			}
		}
		
		T filter(Struct.Pool<IO> ioPool, IO instance, List<FieldAccessor<IO>> dependencies, T value);
	}
	
	public enum StoragePool{
		/**
		 * Values in this storage pool remain as long as the instance is alive.
		 */
		INSTANCE("<<"),
		/**
		 * Values in this storage pool remain as long as there is an IO operation is executing.
		 * Used for fields that are only needed to correctly read another field such as length of an array.
		 */
		IO("IO");
		
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
		
		if(annotations.stream().noneMatch(an->an instanceof IOValue)){
			annotations=Stream.concat(annotations.stream(), Stream.of(IOFieldTools.makeAnnotation(IOValue.class))).toList();
		}
		this.annotations=GetAnnotation.from(annotations);
	}
	
	public String getName()                  {return name;}
	public Type getType()                    {return type;}
	public GetterFilter<IO, T> getGetFilter(){return getFilter;}
	public GetAnnotation getAnnotations()    {return annotations;}
	
	@Override
	public String toString(){
		return getName()+": "+Utils.typeToHuman(type, false);
	}
}
