package com.lapissea.jorth;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.ConsoleColors;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Pattern;

final class DefinitionPrinter{
	private static final Pattern       PACKAGE_SEGMENT = Pattern.compile("(\\p{javaJavaIdentifierStart})\\p{javaJavaIdentifierPart}*\\.(?=\\p{javaJavaIdentifierStart})");
	private final        StringBuilder text            = new StringBuilder();
	private              int           indent;
	
	void line(String value){
		if(!value.isEmpty()) text.append("    ".repeat(indent)).append(value);
		text.append('\n');
	}
	void open(String header){
		line(header + " {");
		indent++;
	}
	void close(){
		indent--;
		line("}");
	}
	@Override
	public String toString(){ return text.toString(); }
	
	static String keyword(String value){ return ConsoleColors.PURPLE_BRIGHT + value + ConsoleColors.RESET; }
	static String name(String value)   { return ConsoleColors.CYAN_BRIGHT + escape(value, '\0') + ConsoleColors.RESET; }
	static String comment(String value){ return ConsoleColors.BLACK_BRIGHT + value + ConsoleColors.RESET; }
	static String type(Object value){
		String result = switch(value){
			case null -> "void";
			case ClassName n -> n.dotted();
			case Class<?> c -> c.getTypeName();
			case GenericType g -> plainType(g);
			default -> value.toString();
		};
		return ConsoleColors.BLUE_BRIGHT + PACKAGE_SEGMENT.matcher(result).replaceAll("$1.") + ConsoleColors.RESET;
	}
	private static String typeText(GenericType type){
		var args = new StringJoiner(", ", "<", ">");
		for(var arg : type.args()) args.add(plainType(arg));
		return type.raw().dotted() + (type.args().isEmpty()? "" : args) + "[]".repeat(type.dims());
	}
	private static String plainType(JType type){
		return type instanceof GenericType g? g.typeArgName().map(n -> n.dotted() + "[]".repeat(g.dims())).orElseGet(() -> typeText(g)) : type.toString();
	}
	static String types(Collection<?> values){
		var result = new StringJoiner(", ");
		for(var value : values) result.add(type(value));
		return result.toString();
	}
	static String modifiers(Visibility visibility, AccessSet access){
		var result = new StringJoiner(" ");
		if(visibility != Visibility.PACKAGE_PRIVATE) result.add(keyword(visibility.name().toLowerCase(Locale.ROOT)));
		if(access.isStatic()) result.add(keyword("static"));
		if(access.isAbstract()) result.add(keyword("abstract"));
		if(access.isFinal()) result.add(keyword("final"));
		return result.toString();
	}
	static String literal(Object value){
		if(value == null) return keyword("null");
		if(value.getClass().isArray()){
			var result = new StringJoiner(", ", "[", "]");
			for(int i = 0; i<Array.getLength(value); i++) result.add(literal(Array.get(value, i)));
			return result.toString();
		}
		if(value instanceof AnnotationDefinition ann) return ann.print();
		if(value instanceof Enum<?> e) return type(e.getDeclaringClass()) + "." + name(e.name());
		if(value instanceof Class<?> || value instanceof ClassName || value instanceof JType) return type(value) + "." + keyword("class");
		if(value instanceof org.objectweb.asm.Type t) return type(t.getClassName()) + "." + keyword("class");
		if(value instanceof String s) return ConsoleColors.GREEN_BRIGHT + '"' + escape(s, '"') + '"' + ConsoleColors.RESET;
		if(value instanceof Character c) return ConsoleColors.GREEN_BRIGHT + "'" + escape(c.toString(), '\'') + "'" + ConsoleColors.RESET;
		return ConsoleColors.YELLOW + escape(value.toString(), '\0') + (value instanceof Long? "L" : value instanceof Float? "F" : "") + ConsoleColors.RESET;
	}
	private static String escape(String value, char quote){
		var result = new StringBuilder();
		for(int i = 0; i<value.length(); i++){
			char c = value.charAt(i);
			switch(c){
				case '\n' -> result.append("\\n");
				case '\r' -> result.append("\\r");
				case '\t' -> result.append("\\t");
				case '\\' -> result.append("\\\\");
				default -> {
					if(c == quote) result.append('\\').append(c);
					else if(Character.isISOControl(c)) result.append(String.format("\\u%04x", (int)c));
					else result.append(c);
				}
			}
		}
		return result.toString();
	}
}
