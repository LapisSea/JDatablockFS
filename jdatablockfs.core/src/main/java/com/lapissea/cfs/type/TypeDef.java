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

public final class TypeDef extends IOInstance<TypeDef>{
	
	public static final class FieldDef extends IOInstance<FieldDef>{
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
		
		public FieldDef(){}
		
		public FieldDef(IOField<?, ?> field){
			type=TypeLink.of(field.getAccessor().getGenericType(null));
			name=field.getName();
			nullability=field.getAccessor().getAnnotation(IONullability.class).map(IONullability::value).orElse(IONullability.Mode.NOT_NULL);
			isDynamic=field.getAccessor().hasAnnotation(IOType.Dynamic.class);
			referenceType=field.getAccessor().getAnnotation(IOValue.Reference.class).map(IOValue.Reference::dataPipeType).orElse(null);
			var deps=field.dependencyStream().map(IOField::getName).collect(Collectors.toSet());
			if(field.getAccessor().getType().isArray()) deps.remove(IOFieldTools.makeCollectionLenName(field.getAccessor()));
			if(isDynamic) deps.remove(IOFieldTools.makeGenericIDFieldName(field.getAccessor()));
			dependencies=deps.toArray(String[]::new);
			unsigned=field.getAccessor().hasAnnotation(IOValue.Unsigned.class);
		}
		
		public TypeLink getType()  {return type;}
		public String getName()    {return name;}
		public boolean isDynamic() {return isDynamic;}
		public boolean isUnsigned(){return unsigned;}
		
		public List<String> getDependencies(){
			return dependencies==null?List.of():ArrayViewList.create(dependencies, null);
		}
		
		public IONullability.Mode getNullability()          {return nullability;}
		public IOValue.Reference.PipeType getReferenceType(){return referenceType;}
		
		@Override
		public String toString(){
			return name+(nullability!=null?" "+nullability:"")+": "+type+(dependencies==null||dependencies.length==0?"":"(deps = ["+String.join(", ", dependencies)+"])");
		}
		@Override
		public String toShortString(){
			return name+(nullability!=null?" "+nullability.shortName:"")+": "+Utils.toShortString(type);
		}
	}
	
	public static final class EnumConstant extends IOInstance<EnumConstant>{
		
		@IOValue
		private String name;
		
		public EnumConstant(){}
		public <T extends Enum<T>> EnumConstant(T constant){
			name=constant.name();
		}
		
		public String getName(){
			return name;
		}
	}
	
	
	@IOValue
	private boolean ioInstance;
	@IOValue
	private boolean unmanaged;
	
	@IOValue
	private FieldDef[] fields=new FieldDef[0];
	
	@IOValue
	@IONullability(NULLABLE)
	private EnumConstant[] enumConstants;
	
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
		if(type.isEnum()){
			//noinspection unchecked,rawtypes
			enumConstants=Arrays.stream(((Class<Enum>)type).getEnumConstants())
			                    .map(EnumConstant::new)
			                    .toArray(EnumConstant[]::new);
		}
	}
	
	public boolean isUnmanaged() {return unmanaged;}
	public boolean isIoInstance(){return ioInstance;}
	public boolean isEnum()      {return enumConstants!=null;}
	
	public List<FieldDef> getFields(){
		if(fields==null) return List.of();
		return ArrayViewList.create(fields, null);
	}
	
	public List<EnumConstant> getEnumConstants(){
		if(!isEnum()) return List.of();
		return ArrayViewList.create(enumConstants, null);
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
