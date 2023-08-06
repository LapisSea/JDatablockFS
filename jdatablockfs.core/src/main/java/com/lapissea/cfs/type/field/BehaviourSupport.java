package com.lapissea.cfs.type.field;

import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.VarPool;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOField.FieldUsage.BehaviourRes;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.lapissea.cfs.type.field.StoragePool.INSTANCE;
import static com.lapissea.cfs.type.field.StoragePool.IO;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.GHOST;
import static com.lapissea.cfs.type.field.annotations.IODependency.VirtualNumSize.RetentionPolicy.GROW_ONLY;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;

public final class BehaviourSupport{
	
	public static <T extends IOInstance<T>> BehaviourRes<T> packCompanion(FieldAccessor<T> field){
		return new BehaviourRes<T>(new VirtualFieldDefinition<>(
			StoragePool.IO,
			IOFieldTools.makePackName(field),
			byte[].class
		));
	}
	public static <T extends IOInstance<T>> BehaviourRes<T> referenceCompanion(FieldAccessor<T> field){
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Reference>(
			INSTANCE,
			IOFieldTools.makeRefName(field),
			Reference.class,
			List.of(IOFieldTools.makeNullabilityAnn(DEFAULT_IF_NULL))
		));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> genericID(FieldAccessor<T> field){
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Integer>(
			IO,
			IOFieldTools.makeGenericIDFieldName(field),
			int.class,
			List.of(IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class), IOValue.Unsigned.INSTANCE)
		));
	}
	
	private static <T extends IOInstance<T>> boolean canHaveNullabilityField(FieldAccessor<T> field){
		if(field.hasAnnotation(IOValue.Reference.class)) return false;
		var typ = field.getType();
		if(typ.isArray()) return true;
		if(IOInstance.isInstance(typ)){
			return IOInstance.isManaged(typ);
		}
		return IOFieldTools.isGeneric(field) || FieldCompiler.getWrapperTypes().stream().anyMatch(c -> UtilL.instanceOf(typ, c));
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> ioNullability(FieldAccessor<T> field, IONullability ann){
		if(!IONullability.NullLogic.canHave(field)){
			throw new MalformedStruct(field + " is not a supported field");
		}
		if(SupportedPrimitive.get(field.getType()).isPresent() && ann.value() == DEFAULT_IF_NULL){
			throw new MalformedStruct("Wrapper type on " + field + " does not support " + DEFAULT_IF_NULL + " mode");
		}
		
		if(!IOFieldTools.isNullable(field) || !canHaveNullabilityField(field)) return BehaviourRes.non();
		
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Boolean>(
			StoragePool.IO,
			IOFieldTools.makeNullFlagName(field),
			boolean.class
		));
	}
	public static <T extends IOInstance<T>> BehaviourRes<T> virtualNumSize(FieldAccessor<T> field, IODependency.VirtualNumSize ann){
		var unsigned = field.hasAnnotation(IOValue.Unsigned.class) || field.getType() == ChunkPointer.class;
		
		var retention = ann.retention();
		var min       = ann.min();
		var max       = ann.max();
		
		var vf = new VirtualFieldDefinition<>(
			retention == GHOST? IO : INSTANCE,
			IODependency.VirtualNumSize.Logic.getName(field, ann),
			NumberSize.class,
			new VirtualFieldDefinition.GetterFilter<T, NumberSize>(){
				private NumberSize calcMax(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
					var len = calcMaxVal(ioPool, inst, deps);
					return NumberSize.bySize(len, unsigned);
				}
				private long calcMaxVal(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps){
					return switch(deps.size()){
						case 1 -> deps.get(0).getLong(ioPool, inst);
						case 2 -> {
							long a = deps.get(0).getLong(ioPool, inst);
							long b = deps.get(1).getLong(ioPool, inst);
							yield Math.max(a, b);
						}
						default -> {
							long best = Long.MIN_VALUE;
							for(var d : deps){
								long newVal = d.getLong(ioPool, inst);
								if(newVal>best){
									best = newVal;
								}
							}
							yield best;
						}
					};
				}
				@Override
				public NumberSize filter(VarPool<T> ioPool, T inst, List<FieldAccessor<T>> deps, NumberSize val){
					NumberSize raw;
					
					if(retention == GROW_ONLY){
						if(val == max) raw = max;
						else raw = calcMax(ioPool, inst, deps).max(val == null? NumberSize.VOID : val);
					}else{
						raw = val == null? calcMax(ioPool, inst, deps) : val;
					}
					
					var size = raw.max(min);
					
					if(size.greaterThan(max)){
						throw new RuntimeException(size + " can't fit in to " + max);
					}
					
					return size;
				}
			});
		
		return new BehaviourRes<>(vf);
	}
	
	public static <T extends IOInstance<T>> BehaviourRes<T> reference(FieldAccessor<T> field){
		assert field.hasAnnotation(IOValue.Reference.class);
		return new BehaviourRes<>(new VirtualFieldDefinition<T, Reference>(
			INSTANCE,
			IOFieldTools.makeRefName(field),
			Reference.class,
			List.of(IOFieldTools.makeNullabilityAnn(DEFAULT_IF_NULL))
		));
	}
	public static <T extends IOInstance<T>> BehaviourRes<T> collectionLength(FieldAccessor<T> field){
		if(field.hasAnnotation(IOValue.Reference.class)) return BehaviourRes.non();
		var type   = field.getType();
		var isList = type == List.class || type == ArrayList.class;
		if(!type.isArray() && !isList){
			throw new AssertionError(type.getTypeName());
		}
		Set<Class<? extends Annotation>> annotationTouch = new HashSet<>();
		
		var arrayLenSize = field.getAnnotation(IODependency.ArrayLenSize.class);
		arrayLenSize.map(Annotation::annotationType).ifPresent(annotationTouch::add);
		
		var arrayLengthSizeName = arrayLenSize.map(IODependency.ArrayLenSize::name)
		                                      .orElseGet(() -> IOFieldTools.makeNumberSizeName(IOFieldTools.makeCollectionLenName(field)));
		
		boolean needsNumSize = type == int[].class;
		
		var lenField = new VirtualFieldDefinition<>(
			IO, IOFieldTools.makeCollectionLenName(field), int.class,
			(VirtualFieldDefinition.GetterFilter.I<T>)(ioPool, instance, dependencies, value) -> {
				if(value>0) return value;
				var collection = instance == null? null : field.get(ioPool, instance);
				if(collection != null){
					if(isList) return ((List<?>)collection).size();
					return Array.getLength(collection);
				}
				return 0;
			},
			List.of(
				IOFieldTools.makeAnnotation(IODependency.VirtualNumSize.class, Map.of("name", arrayLengthSizeName)),
				IOValue.Unsigned.INSTANCE
			));
		
		
		
		if(needsNumSize){
			var numSizField = new VirtualFieldDefinition<T, NumberSize>(IO, IOFieldTools.makeNumberSizeName(field), NumberSize.class);
			return new BehaviourRes<>(List.of(lenField, numSizField), annotationTouch);
		}
		
		return new BehaviourRes<>(List.of(lenField), annotationTouch);
	}
	
	
}
