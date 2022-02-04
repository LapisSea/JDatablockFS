package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ArrayViewList;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class TypeDef extends IOInstance<TypeDef>{
	
	public static class FieldDef extends IOInstance<FieldDef>{
		@IOValue
		private TypeLink type;
		
		@IOValue
		private String name;
		
		public FieldDef(){}
		
		public FieldDef(IOField<?, ?> field){
			type=TypeLink.of(field.getAccessor().getGenericType(null));
			name=field.getName();
		}
		
		public TypeLink getType(){
			return type;
		}
		public String getName(){
			return name;
		}
		@Override
		public String toString(){
			return name+": "+type;
		}
		@Override
		public String toShortString(){
			return name+": "+TextUtil.toShortString(type);
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
		ioInstance=UtilL.instanceOf(type, IOInstance.class);
		unmanaged=UtilL.instanceOf(type, IOInstance.Unmanaged.class);
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
