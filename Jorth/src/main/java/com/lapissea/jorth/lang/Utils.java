package com.lapissea.jorth.lang;

import java.util.stream.Collectors;

public class Utils{
	
	public static String genericSignature(GenType sig){
		var primitive=switch(sig.typeName()){
			case "boolean" -> "Z";
			case "void" -> "V";
			case "char" -> "C";
			case "byte" -> "B";
			case "short" -> "S";
			case "int" -> "I";
			case "long" -> "J";
			case "float" -> "F";
			case "double" -> "D";
			default -> null;
		};
		if(primitive!=null){
			return primitive;
		}
		
		String argStr;
		if(sig.args().isEmpty()) argStr="";
		else{
			argStr=sig.args().stream().map(Utils::genericSignature).collect(Collectors.joining("", "<", ">"));
		}
		return "L"+undotify(sig.typeName())+argStr+";";
	}
	
	public static String undotify(String className){
		return className.replace('.', '/');
	}
}
