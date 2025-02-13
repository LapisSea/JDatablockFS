package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.util.TextUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import static com.lapissea.dfs.type.SupportedPrimitive.BOOLEAN;

public final class CompilationTools{
	
	enum Style{
		NAMED("FieldType getName() / void setName(FieldType newValue)"),
		RAW("FieldType name() / void name(FieldType newValue)");
		
		final String humanPattern;
		Style(String humanPattern)        { this.humanPattern = humanPattern; }
		
		String mapGetter(String fieldName){ return mapName(true, fieldName); }
		String mapSetter(String fieldName){ return mapName(false, fieldName); }
		String mapName(boolean isGetter, String fieldName){
			return switch(this){
				case NAMED -> (isGetter? "get" : "set") + TextUtil.firstToUpperCase(fieldName);
				case RAW -> fieldName;
			};
		}
	}
	
	record FieldStub(Method method, String varName, Type type, Style style, boolean isGetter){
		@Override
		public String toString(){
			return varName + "(" + style + ")";
		}
	}
	
	private static Match<String> namePrefix(Method m, String prefix){
		var name = m.getName();
		if(name.length()<=prefix.length()) return Match.empty();
		if(!name.startsWith(prefix)) return Match.empty();
		if(Character.isLowerCase(name.charAt(prefix.length()))) return Match.empty();
		return Match.of(TextUtil.firstToLowerCase(name.substring(prefix.length())));
	}
	
	static Match<FieldStub> asStub(Method method){
		if(asGetterStub(method) instanceof Some<FieldStub> getter) return getter;
		return asSetterStub(method);
	}
	
	static Match<FieldStub> asGetterStub(Method method){
		find:
		{// FieldType getName()
			if(method.getParameterCount() != 0) break find;
			var type = method.getGenericReturnType();
			if(type == void.class) break find;
			
			if(namePrefix(method, "get") instanceof Some(var name)){
				return Match.of(new FieldStub(method, name, type, Style.NAMED, true));
			}
		}
		find:
		{// (b/B)oolean isName()
			if(method.getParameterCount() != 0) break find;
			var type = method.getGenericReturnType();
			if(SupportedPrimitive.get(type).orElse(null) != BOOLEAN) break find;
			
			if(namePrefix(method, "is") instanceof Some(var name)){
				return Match.of(new FieldStub(method, name, type, Style.NAMED, true));
			}
		}
		find:
		{// FieldType name()
			if(method.getParameterCount() != 0) break find;
			var name = method.getName();
			var type = method.getGenericReturnType();
			if(type == void.class) break find;
			
			return Match.of(new FieldStub(method, name, type, Style.RAW, true));
		}
		
		return Match.empty();
	}
	static Match<FieldStub> asSetterStub(Method method){
		find:
		{// void setName(FieldType newValue)
			if(method.getParameterCount() != 1) break find;
			if(method.getReturnType() != void.class) break find;
			
			if(namePrefix(method, "set") instanceof Some(var name)){
				return Match.of(new FieldStub(method, name, method.getGenericParameterTypes()[0], Style.NAMED, false));
			}
		}
		find:
		{// void name(FieldType newValue)
			if(method.getParameterCount() != 1) break find;
			if(method.getReturnType() != void.class) break find;
			var name = method.getName();
			var type = method.getGenericParameterTypes()[0];
			return Match.of(new FieldStub(method, name, type, Style.RAW, false));
		}
		
		return Match.empty();
	}
}
