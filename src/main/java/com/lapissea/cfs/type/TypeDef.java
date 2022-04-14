package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class TypeDef extends IOInstance<TypeDef>{
	
	public static class FieldDef extends IOInstance<FieldDef>{
		@IOValue
		private TypeLink type;
		
		@IOValue
		private String name;
		
		@IOValue
		@IONullability(NULLABLE)
		private IONullability.Mode nullability;
		
		@IOValue
		private boolean isDynamic;
		
		@IOValue
		private String[] dependencies;
		
		public FieldDef(){}
		
		public FieldDef(IOField<?, ?> field){
			type=TypeLink.of(field.getAccessor().getGenericType(null));
			name=field.getName();
			nullability=field.getAccessor().getAnnotation(IONullability.class).map(IONullability::value).orElse(null);
			isDynamic=field.getAccessor().hasAnnotation(IOType.Dynamic.class);
			var deps=field.dependencyStream().map(IOField::getName).collect(Collectors.toSet());
			if(field.getAccessor().getType().isArray()) deps.remove(IOFieldTools.makeArrayLenName(field.getAccessor()));
			if(isDynamic) deps.remove(IOFieldTools.makeGenericIDFieldName(field.getAccessor()));
			dependencies=deps.toArray(String[]::new);
		}
		
		public TypeLink getType(){
			return type;
		}
		public String getName(){
			return name;
		}
		public IONullability.Mode getNullability(){
			return nullability;
		}
		public boolean isDynamic(){
			return isDynamic;
		}
		public List<String> getDependencies(){
			return dependencies==null?List.of():ArrayViewList.create(dependencies, null);
		}
		
		@Override
		public String toString(){
			return name+(nullability!=null?" "+nullability:"")+": "+type+(dependencies==null||dependencies.length==0?"":"(deps = ["+String.join(", ", dependencies)+"])");
		}
		@Override
		public String toShortString(){
			return name+(nullability!=null?" "+nullability.shortName:"")+": "+Utils.toShortString(type);
		}
	}
	
	@IOValue
	private boolean ioInstance;
	@IOValue
	private boolean unmanaged;
	
	@IOValue
	private FieldDef[] fields=new FieldDef[0];
	
	public TypeDef(){}
	
	public TypeDef(@NotNull Class<?> type){
		Objects.requireNonNull(type);
		ioInstance=IOInstance.isInstance(type);
		unmanaged=IOInstance.isUnmanaged(type);
		if(ioInstance){
			if(!Modifier.isAbstract(type.getModifiers())){
				fields=Struct.ofUnknown(type).getFields().stream().map(FieldDef::new).toArray(FieldDef[]::new);
			}
		}
	}
	
	public boolean isUnmanaged() {return unmanaged;}
	public boolean isIoInstance(){return ioInstance;}
	
	public List<FieldDef> getFields(){
		if(fields==null) return List.of();
		return ArrayViewList.create(fields, null);
	}
	
	@Override
	public String toShortString(){
		return Arrays.stream(fields).map(FieldDef::toShortString).collect(Collectors.joining(", ", (ioInstance?"IO":"")+(unmanaged?"U":"")+"{", "}"));
	}
	@Override
	public String toString(){
		return Arrays.stream(fields).map(FieldDef::toString).collect(Collectors.joining(", ", (ioInstance?"IO":"")+(unmanaged?"U":"")+"{", "}"));
	}
}
