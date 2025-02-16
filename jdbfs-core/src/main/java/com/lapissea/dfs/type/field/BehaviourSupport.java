package com.lapissea.dfs.type.field;

import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.IOField.FieldUsage.BehaviourRes;
import com.lapissea.dfs.type.field.access.FieldAccessor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lapissea.dfs.type.field.StoragePool.INSTANCE;
import static com.lapissea.dfs.type.field.StoragePool.IO;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public final class BehaviourSupport{
	
	public static <T extends IOInstance<T>> BehaviourRes<T> packCompanion(FieldAccessor<T> field){
		return new BehaviourRes<T>(new VirtualFieldDefinition<>(
			StoragePool.IO,
			FieldNames.pack(field),
			byte[].class
		));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> referenceCompanion(FieldAccessor<T> field){
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Reference>(
			INSTANCE,
			FieldNames.ref(field),
			Reference.class,
			List.of(Annotations.makeNullability(DEFAULT_IF_NULL))
		));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> genericID(FieldAccessor<T> field){
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Integer>(
			IO,
			FieldNames.genericID(field),
			int.class,
			List.of(Annotations.make(IODependency.VirtualNumSize.class), IOValue.Unsigned.INSTANCE)
		));
	}
	
	private static <T extends IOInstance<T>> boolean canHaveNullabilityField(FieldAccessor<T> field){
		if(field.hasAnnotation(IOValue.Reference.class)) return false;
		var typ = field.getType();
		if(typ.isArray() || UtilL.instanceOf(typ, Collection.class) || UtilL.instanceOf(typ, Type.class)) return true;
		if(IOInstance.isInstance(typ)){
			return IOInstance.isManaged(typ);
		}
		return IOFieldTools.isGeneric(field) || Iters.from(FieldCompiler.getWrapperTypes()).anyMatch(c -> UtilL.instanceOf(typ, c));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> ioNullability(FieldAccessor<T> field, IONullability ann){
		if(SupportedPrimitive.get(field.getType()).isPresent() && ann.value() == DEFAULT_IF_NULL){
			throw new MalformedStruct("fmt", "Wrapper type on {}#yellow does not support {}#red mode", field, DEFAULT_IF_NULL);
		}
		
		if(!IOFieldTools.isNullable(field)){
			return BehaviourRes.non();
		}
		
		if(!canHaveNullabilityField(field)){
			throw new ShouldNeverHappenError();//TODO: remove this when fully tested
		}
		
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Boolean>(
			StoragePool.IO,
			FieldNames.nullFlag(field),
			boolean.class
		));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> virtualNumSize(FieldAccessor<T> field, IODependency.VirtualNumSize ann){
		return new BehaviourRes<>(new VirtualFieldDefinition<>(
			IO,
			IOFieldTools.getNumSizeName(field, ann),
			NumberSize.class,
			new GetNumberSize.Uninitialized<T>(
				ann.min(), ann.max(),
				field.hasAnnotation(IOValue.Unsigned.class) || field.getType() == ChunkPointer.class
			)
		));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> reference(FieldAccessor<T> field){
		assert field.hasAnnotation(IOValue.Reference.class);
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Reference>(
			INSTANCE,
			FieldNames.ref(field),
			Reference.class,
			List.of(Annotations.makeNullability(DEFAULT_IF_NULL))
		));
	}
	
	private static <T extends IOInstance<T>> VirtualFieldDefinition.GetterFilter<T, Integer> arryFilter(FieldAccessor<T> field){
		return new VirtualFieldDefinition.GetterFilter<>(){
			@Override
			public Integer filter(VarPool<T> ioPool, T instance, Integer value){
				if(value>0) return value;
				if(instance == null) return 0;
				var data = field.get(ioPool, instance);
				if(data == null) return 0;
				return Array.getLength(data);
			}
			@Override
			public VirtualFieldDefinition.GetterFilter<T, Integer> withUsers(List<FieldAccessor<T>> users){ return this; }
		};
	}
	private static <T extends IOInstance<T>> VirtualFieldDefinition.GetterFilter<T, Integer> collectionFilter(FieldAccessor<T> field){
		return new VirtualFieldDefinition.GetterFilter<>(){
			@Override
			public Integer filter(VarPool<T> ioPool, T instance, Integer value){
				if(value>0) return value;
				if(instance == null) return 0;
				var data = field.get(ioPool, instance);
				if(data == null) return 0;
				return ((Collection<?>)data).size();
			}
			@Override
			public VirtualFieldDefinition.GetterFilter<T, Integer> withUsers(List<FieldAccessor<T>> users){ return this; }
		};
	}
	private static <T extends IOInstance<T>> VirtualFieldDefinition.GetterFilter<T, Integer> bufferFilter(FieldAccessor<T> field){
		return new VirtualFieldDefinition.GetterFilter<>(){
			@Override
			public Integer filter(VarPool<T> ioPool, T instance, Integer value){
				if(value>0) return value;
				if(instance == null) return 0;
				var data = field.get(ioPool, instance);
				if(data == null) return 0;
				return ((Buffer)data).capacity();
			}
			@Override
			public VirtualFieldDefinition.GetterFilter<T, Integer> withUsers(List<FieldAccessor<T>> users){ return this; }
		};
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> collectionLength(FieldAccessor<T> field){
		
		enum FType{
			ARRAY,
			COLLECTION,
			BB
		}
		
		var   type = field.getType();
		FType fType;
		if(type.isArray()) fType = FType.ARRAY;
		else if(UtilL.instanceOf(type, Collection.class)) fType = FType.COLLECTION;
		else if(type == ByteBuffer.class) fType = FType.BB;
		else{
			throw new AssertionError(type.getTypeName());
		}
		
		Set<Class<? extends Annotation>> annotationTouch = new HashSet<>();
		
		var    arrayLenSize = field.getAnnotation(IODependency.ArrayLenSize.class);
		String arrayLengthSizeName;
		if(arrayLenSize instanceof Some(var ann)){
			annotationTouch.add(ann.annotationType());
			arrayLengthSizeName = ann.name();
		}else{
			arrayLengthSizeName = FieldNames.numberSize(FieldNames.name(FieldNames.collectionLen(field)));
		}
		
		boolean needsNumSize = type == int[].class;
		
		var lenField = new VirtualFieldDefinition<>(
			IO, FieldNames.collectionLen(field), int.class,
			switch(fType){
				case ARRAY -> arryFilter(field);
				case COLLECTION -> collectionFilter(field);
				case BB -> bufferFilter(field);
			},
			List.of(
				Annotations.make(IODependency.VirtualNumSize.class, Map.of("name", arrayLengthSizeName)),
				IOValue.Unsigned.INSTANCE
			));
		
		
		
		if(needsNumSize){
			var numSizField = new VirtualFieldDefinition<T, NumberSize>(IO, FieldNames.numberSize(field), NumberSize.class);
			return new BehaviourRes<>(List.of(lenField, numSizField), annotationTouch);
		}
		
		return new BehaviourRes<>(List.of(lenField), annotationTouch);
	}
	
	
}
