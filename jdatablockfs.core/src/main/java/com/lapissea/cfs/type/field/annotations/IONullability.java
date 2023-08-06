package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.SupportedPrimitive;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.compilation.FieldCompiler;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.StoragePool;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.AnnotatedType;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
public @interface IONullability{
	
	@Target({ElementType.FIELD, ElementType.METHOD})
	@interface Elements{
		
		
		AnnotationLogic<Elements> LOGIC = new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, Elements annotation){
				var typ = field.getType();
				if(!typ.isArray()) throw new MalformedStruct(Elements.class.getName() + " can be used only on arrays");
				if(IOInstance.isInstance(typ.componentType())){
					throw new MalformedStruct(Elements.class.getName() + " array must be of " + IOInstance.class.getName() + " type");
				}
			}
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Elements annotation){
				if(annotation.value() != Mode.NULLABLE) return List.of();
				
				return List.of(new VirtualFieldDefinition<T, boolean[]>(
					StoragePool.IO,
					IOFieldTools.makeNullElementsFlagName(field),
					boolean[].class,
					(ioPool, instance, dependencies, value) -> {
						if(value != null) return value;
						
						var instances = (IOInstance<?>[])field.get(ioPool, instance);
						if(instances == null) return null;
						
						var noNulls = true;
						var gen     = new boolean[instances.length];
						for(int i = 0; i<instances.length; i++){
							var nl = instances[i] == null;
							if(nl) noNulls = false;
							gen[i] = nl;
						}
						if(noNulls) return null;
						return gen;
					},
					List.of(IOFieldTools.makeNullabilityAnn(IOFieldTools.getNullability(field)))
				));
			}
		};
		
		Mode value() default Mode.NOT_NULL;
	}
	
	final class NullLogic implements AnnotationLogic<IONullability>{
		@Override
		public void validate(FieldAccessor<?> field, IONullability annotation){
			if(!canHave(field)){
				throw new MalformedStruct(field + " is not a supported field");
			}
			if(SupportedPrimitive.get(field.getType()).isPresent() && annotation.value() == Mode.DEFAULT_IF_NULL){
				throw new MalformedStruct("Wrapper type on " + field + " does not support " + Mode.DEFAULT_IF_NULL + " mode");
			}
		}
		
		public static boolean canHave(AnnotatedType field){
			var typ = field.getType();
			if(typ.isPrimitive()) return false;
			return IOFieldTools.isGeneric(field) || typ.isArray() ||
			       Stream.of(
				       Stream.of(IOInstance.class, Enum.class),
				       FieldCompiler.getWrapperTypes().stream(),
				       Arrays.stream(SupportedPrimitive.values()).map(p -> p.wrapper)
			       ).<Class<?>>flatMap(Function.identity()).anyMatch(c -> UtilL.instanceOf(typ, c));
		}
		
		
		private <T extends IOInstance<T>> boolean canHaveNullabilityField(FieldAccessor<T> field){
			if(field.hasAnnotation(IOValue.Reference.class)) return false;
			var typ = field.getType();
			if(typ.isArray()) return true;
			if(IOInstance.isInstance(typ)){
				return IOInstance.isManaged(typ);
			}
			return IOFieldTools.isGeneric(field) || FieldCompiler.getWrapperTypes().stream().anyMatch(c -> UtilL.instanceOf(typ, c));
		}
		
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, IONullability annotation){
			if(!IOFieldTools.isNullable(field) || !canHaveNullabilityField(field)) return List.of();
			
			return List.of(new VirtualFieldDefinition<T, Boolean>(
				StoragePool.IO,
				IOFieldTools.makeNullFlagName(field),
				boolean.class
			));
		}
	}
	
	AnnotationLogic<IONullability> LOGIC = new NullLogic();
	
	enum Mode{
		NOT_NULL("NN"),
		NULLABLE("n"),
		DEFAULT_IF_NULL("DN");
		
		public final String shortName;
		Mode(String shortName){ this.shortName = shortName; }
	}
	
	Mode value() default Mode.NOT_NULL;
	
}
