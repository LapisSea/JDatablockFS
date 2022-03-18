package com.lapissea.cfs.type.field.annotations;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.compilation.AnnotationLogic;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Retention(RetentionPolicy.RUNTIME)
public @interface IONullability{
	
	@interface Elements{
		
		
		AnnotationLogic<Elements> LOGIC=new AnnotationLogic<>(){
			@Override
			public void validate(FieldAccessor<?> field, Elements annotation){
				var typ=field.getType();
				if(!typ.isArray()) throw new MalformedStructLayout(Elements.class.getName()+" can be used only on arrays");
				if(UtilL.instanceOf(typ.componentType(), IOInstance.class)){
					throw new MalformedStructLayout(Elements.class.getName()+" array must be of "+IOInstance.class.getName()+" type");
				}
			}
			
			@NotNull
			@Override
			public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, Elements annotation){
				if(annotation.value()!=Mode.NULLABLE) return List.of();
				
				return List.of(new VirtualFieldDefinition<T, boolean[]>(
					VirtualFieldDefinition.StoragePool.IO,
					IOFieldTools.makeNullElementsFlagName(field),
					boolean[].class,
					(ioPool, instance, dependencies, value)->{
						if(value!=null) return value;
						
						var instances=(IOInstance<?>[])field.get(ioPool, instance);
						if(instances==null) return null;
						
						var noNulls=true;
						var gen    =new boolean[instances.length];
						for(int i=0;i<instances.length;i++){
							var nl=instances[i]==null;
							if(nl) noNulls=false;
							gen[i]=nl;
						}
						if(noNulls) return null;
						return gen;
					},
					List.of(IOFieldTools.makeAnnotation(IONullability.class, Map.of("value", field.getAnnotation(IONullability.class).map(IONullability::value).orElse(Mode.NOT_NULL))))
				));
			}
			
			@NotNull
			@Override
			public Set<String> getDependencyValueNames(FieldAccessor<?> field, Elements annotation){
				if(annotation.value()!=Mode.NULLABLE) return Set.of();
				
				return Set.of(IOFieldTools.makeNullElementsFlagName(field));
			}
			
			
		};
		
		Mode value() default Mode.NOT_NULL;
	}
	
	AnnotationLogic<IONullability> LOGIC=new AnnotationLogic<>(){
		@Override
		public void validate(FieldAccessor<?> field, IONullability annotation){
			var typ=field.getType();
			if(Stream.of(IOInstance.class, Enum.class, String.class).noneMatch(c->UtilL.instanceOf(typ, c))&&!field.hasAnnotation(IOType.Dynamic.class)){
				throw new MalformedStructLayout(field+" is not a supported field");
			}
		}
		
		
		private <T extends IOInstance<T>> boolean isNullable(FieldAccessor<T> field){
			return field.getAnnotation(IONullability.class).map(IONullability::value).orElseThrow()==IONullability.Mode.NULLABLE;
		}
		
		private <T extends IOInstance<T>> boolean canHaveNullabilityField(FieldAccessor<T> field){
			if(field.hasAnnotation(IOValue.Reference.class)) return false;
			if(UtilL.instanceOf(field.getType(), IOInstance.class)){
				return !UtilL.instanceOf(field.getType(), IOInstance.Unmanaged.class);
			}
			return UtilL.instanceOf(field.getType(), String.class)||field.hasAnnotation(IOType.Dynamic.class);
		}
		
		@NotNull
		@Override
		public <T extends IOInstance<T>> List<VirtualFieldDefinition<T, ?>> injectPerInstanceValue(FieldAccessor<T> field, IONullability annotation){
			if(!canHaveNullabilityField(field)) return List.of();
			if(!isNullable(field)) return List.of();
			
			return List.of(new VirtualFieldDefinition<T, Boolean>(
				VirtualFieldDefinition.StoragePool.IO,
				IOFieldTools.makeNullFlagName(field),
				Boolean.class,
				(ioPool, instance, dependencies, value)->value==null||value,
				List.of()
			));
		}
		
		@NotNull
		@Override
		public Set<String> getDependencyValueNames(FieldAccessor<?> field, IONullability annotation){
			if(!canHaveNullabilityField(field)) return Set.of();
			if(!isNullable(field)) return Set.of();
			
			return Set.of(IOFieldTools.makeNullFlagName(field));
		}
	};
	
	enum Mode{
		NOT_NULL("NN"),
		NULLABLE("n"),
		DEFAULT_IF_NULL("DN");
		
		public final String shortName;
		Mode(String shortName){this.shortName=shortName;}
	}
	
	Mode value() default Mode.NOT_NULL;
	
}
