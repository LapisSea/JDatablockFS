package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.type.TypeLink;
import com.lapissea.util.NotImplementedException;

public class JorthUtils{
	
	public static String toJorthGeneric(TypeLink typ){
		StringBuilder sb   = new StringBuilder();
		var           dims = 0;
		if(typ.getTypeName().startsWith("[")){
			var nam = typ.getTypeName();
			while(nam.startsWith("[")){
				dims++;
				nam = nam.substring(1);
			}
			sb.append(switch(nam){
				case "B" -> "byte";
				case "S" -> "short";
				case "I" -> "int";
				case "J" -> "long";
				case "F" -> "float";
				case "D" -> "double";
				case "C" -> "char";
				case "Z" -> "boolean";
				default -> {
					if(!nam.startsWith("L") || !nam.endsWith(";")) throw new NotImplementedException("Unknown tyoe: " + nam);
					yield nam.substring(1, nam.length() - 1);
				}
			});
		}else{
			sb.append(typ.getTypeName());
		}
		
		if(typ.argCount()>0){
			sb.append('<');
			for(int i = 0; i<typ.argCount(); i++){
				var arg = typ.arg(i);
				sb.append(toJorthGeneric(arg)).append(' ');
			}
			sb.append('>');
		}
		
		sb.append(" array".repeat(dims));
		return sb.toString();
	}
	
}
