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
import java.util.Set;
import java.util.stream.Stream;

@Retention(RetentionPolicy.RUNTIME)
public @interface IONullability{
	
	AnnotationLogic<IONullability> LOGIC=new AnnotationLogic<>(){
		@Override
		public void validate(FieldAccessor<?> field, IONullability annotation){
			var typ=field.getType();
			if(Stream.of(IOInstance.class, Enum.class).noneMatch(c->UtilL.instanceOf(typ, c))&&!field.hasAnnotation(IOType.Dynamic.class)){
				throw new MalformedStructLayout(field+" is not a supported field");
			}
		}
		
		
		private <T extends IOInstance<T>> boolean isNullable(FieldAccessor<T> field){
			return field.getAnnotation(IONullability.class).map(IONullability::value).orElseThrow()==IONullability.Mode.NULLABLE;
		}
		
		private <T extends IOInstance<T>> boolean canHaveNullabilityField(FieldAccessor<T> field){
			if(field.hasAnnotation(IOValue.Reference.class)) return false;
			return UtilL.instanceOf(field.getType(), IOInstance.class)||field.hasAnnotation(IOType.Dynamic.class);
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
		NOT_NULL,
		NULLABLE,
		DEFAULT_IF_NULL
	}
	
	Mode value() default Mode.NOT_NULL;
	
}
