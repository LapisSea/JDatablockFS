package com.lapissea.cfs.type;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public final class TypeDef extends IOInstance.Managed<TypeDef>{
	
	public static final class FieldDef extends IOInstance.Managed<FieldDef>{
		@IOValue
		private TypeLink type;
		
		@IOValue
		private String name;
		
		@IOValue
		private boolean isDynamic;
		
		@IOValue
		private boolean unsigned;
		
		@IOValue
		private String[] dependencies;
		
		@IOValue
		private IONullability.Mode nullability;
		
		@IOValue
		@IONullability(NULLABLE)
		private IOValue.Reference.PipeType referenceType;
		
		public FieldDef(){ }
		
		public FieldDef(IOField<?, ?> field){
			type = TypeLink.of(field.getAccessor().getGenericType(null));
			name = field.getName();
			nullability = IOFieldTools.getNullability(field);
			isDynamic = IOFieldTools.isGeneric(field);
			referenceType = field.getAccessor().getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElse(null);
			var deps = field.dependencyStream().map(IOField::getName).collect(Collectors.toSet());
			if(field.getType().isArray()) deps.remove(IOFieldTools.makeCollectionLenName(field.getAccessor()));
			if(isDynamic) deps.remove(IOFieldTools.makeGenericIDFieldName(field.getAccessor()));
			dependencies = deps.toArray(String[]::new);
			unsigned = field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
		}
		
		public TypeLink getType()  { return type; }
		public String getName()    { return name; }
		public boolean isDynamic() { return isDynamic; }
		public boolean isUnsigned(){ return unsigned; }
		
		public List<String> getDependencies(){
			return dependencies == null? List.of() : ArrayViewList.create(dependencies, null);
		}
		
		public IONullability.Mode getNullability()          { return nullability; }
		public IOValue.Reference.PipeType getReferenceType(){ return referenceType; }
		
		@Override
		public String toString(){
			return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability : "") + ": " + type.toString() + (dependencies == null || dependencies.length == 0? "" : "(deps = [" + String.join(", ", dependencies) + "])");
		}
		@Override
		public String toShortString(){
			return name + (nullability != IONullability.Mode.NOT_NULL? " " + nullability.shortName : "") + ": " + Utils.toShortString(type);
		}
	}
	
	@IOInstance.Def.ToString(name = false, fNames = false, curly = false)
	public interface EnumConstant extends IOInstance.Def<EnumConstant>{
		
		private static EnumConstant of(Enum<?> e){
			return IOInstance.Def.of(EnumConstant.class, e.name());
		}
		
		String getName();
	}
	
	
	@IOValue
	private boolean ioInstance;
	@IOValue
	private boolean unmanaged;
	
	@IOValue
	private FieldDef[] fields = new FieldDef[0];
	
	@IOValue
	@IONullability(NULLABLE)
	private EnumConstant[] enumConstants;
	
	public TypeDef(){ }
	
	public TypeDef(@NotNull Class<?> type){
		Objects.requireNonNull(type);
		ioInstance = IOInstance.isInstance(type);
		unmanaged = IOInstance.isUnmanaged(type);
		if(ioInstance){
			if(!Modifier.isAbstract(type.getModifiers()) || UtilL.instanceOf(type, IOInstance.Def.class)){
				fields = Struct.ofUnknown(type).getFields().stream().map(FieldDef::new).toArray(FieldDef[]::new);
			}
		}
		if(type.isEnum()){
			//noinspection unchecked,rawtypes
			enumConstants = Arrays.stream(((Class<Enum>)type).getEnumConstants())
			                      .map(EnumConstant::of)
			                      .toArray(EnumConstant[]::new);
		}
	}
	
	public boolean isUnmanaged() { return unmanaged; }
	public boolean isIoInstance(){ return ioInstance; }
	public boolean isEnum()      { return enumConstants != null; }
	
	public List<FieldDef> getFields(){
		if(fields == null) return List.of();
		return ArrayViewList.create(fields, null);
	}
	
	public List<EnumConstant> getEnumConstants(){
		if(!isEnum()) return List.of();
		return ArrayViewList.create(enumConstants, null);
	}
	@Override
	public String toShortString(){
		return toString();
	}
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		if(isIoInstance()) sb.append("IO");
		if(isUnmanaged()) sb.append("U");
		sb.append('{');
		if(!getEnumConstants().isEmpty()){
			sb.append(getEnumConstants().stream().map(Objects::toString).collect(Collectors.joining(", ")));
		}
		sb.append(Arrays.stream(fields).map(FieldDef::toShortString).collect(Collectors.joining(", ")));
		sb.append('}');
		
		return sb.toString();
	}
}
