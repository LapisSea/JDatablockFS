package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.lang.ClassName;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

public interface FieldInfo{
	
	class OfReflective implements FieldInfo{
		private final Field       field;
		private final ClassName   owner;
		private final GenericType type;
		public OfReflective(Field field){
			this.field = field;
			owner = ClassName.of(field.getDeclaringClass());
			type = GenericType.of(field.getGenericType());
		}
		
		@Override
		public boolean isStatic(){
			return Modifier.isStatic(field.getModifiers());
		}
		@Override
		public ClassName owner(){
			return owner;
		}
		@Override
		public GenericType type(){
			return type;
		}
		@Override
		public String name(){
			return field.getName();
		}
	}
	
	static FieldInfo of(Field field){
		return new OfReflective(field);
	}
	
	boolean isStatic();
	ClassName owner();
	GenericType type();
	String name();
}
