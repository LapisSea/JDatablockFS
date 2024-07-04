package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.type.SupportedPrimitive;
import com.lapissea.util.TextUtil;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
	
	private static Optional<String> namePrefix(Method m, String prefix){
		var name = m.getName();
		if(name.length()<=prefix.length()) return Optional.empty();
		if(!name.startsWith(prefix)) return Optional.empty();
		if(Character.isLowerCase(name.charAt(prefix.length()))) return Optional.empty();
		return Optional.of(TextUtil.firstToLowerCase(name.substring(prefix.length())));
	}
	
	private static final List<Function<Method, Optional<FieldStub>>> GETTER_PATTERNS = List.of(
		m -> {// FieldType getName()
			if(m.getParameterCount() != 0) return Optional.empty();
			var type = m.getGenericReturnType();
			if(type == void.class) return Optional.empty();
			
			return namePrefix(m, "get").map(name -> new FieldStub(m, name, type, Style.NAMED, true));
		},
		m -> {// (b/B)oolean isName()
			if(m.getParameterCount() != 0) return Optional.empty();
			var type = m.getGenericReturnType();
			if(SupportedPrimitive.get(type).orElse(null) != BOOLEAN) return Optional.empty();
			
			return namePrefix(m, "is").map(name -> new FieldStub(m, name, type, Style.NAMED, true));
		},
		m -> {// FieldType name()
			if(m.getParameterCount() != 0) return Optional.empty();
			var name = m.getName();
			var type = m.getGenericReturnType();
			return Optional.of(new FieldStub(m, name, type, Style.RAW, true));
		}
	);
	private static final List<Function<Method, Optional<FieldStub>>> SETTER_PATTERNS = List.of(
		m -> {// void setName(FieldType newValue)
			if(m.getParameterCount() != 1) return Optional.empty();
			if(m.getReturnType() != void.class) return Optional.empty();
			
			return namePrefix(m, "set").map(name -> new FieldStub(m, name, m.getGenericParameterTypes()[0], Style.NAMED, false));
		},
		m -> {// void name(FieldType newValue)
			if(m.getParameterCount() != 1) return Optional.empty();
			if(m.getReturnType() != void.class) return Optional.empty();
			var name = m.getName();
			var type = m.getGenericParameterTypes()[0];
			return Optional.of(new FieldStub(m, name, type, Style.RAW, false));
		}
	);
	
	static Optional<FieldStub> asGetterStub(Method method){
		return GETTER_PATTERNS.stream().map(f -> f.apply(method)).flatMap(Optional::stream).findFirst();
	}
	static Optional<FieldStub> asSetterStub(Method method){
		return SETTER_PATTERNS.stream().map(f -> f.apply(method)).flatMap(Optional::stream).findFirst();
	}
}
