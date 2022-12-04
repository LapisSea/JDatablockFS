package com.lapissea.jorth.lang;

import java.util.stream.Collectors;

public class Utils{
	
	public static String genericSignature(GenType sig){
		return ("[".repeat(sig.arrayDimensions())) + switch(new GenType(sig.typeName(), 0, sig.args()).type()){
			case BOOLEAN -> "Z";
			case VOID -> "V";
			case CHAR -> "C";
			case BYTE -> "B";
			case SHORT -> "S";
			case INT -> "I";
			case LONG -> "J";
			case FLOAT -> "F";
			case DOUBLE -> "D";
			case OBJECT -> {
				String argStr;
				if(sig.args().isEmpty()) argStr = "";
				else{
					argStr = sig.args().stream().map(Utils::genericSignature).collect(Collectors.joining("", "<", ">"));
				}
				yield "L" + undotify(sig.typeName()) + argStr + ";";
			}
		};
		
	}
	
	public static String undotify(String className){
		return className.replace('.', '/');
	}
	
	public static String makeArrayName(String elementName, int dimensions){
		return "[".repeat(dimensions) + "L" + elementName + ";";
	}
	
}
