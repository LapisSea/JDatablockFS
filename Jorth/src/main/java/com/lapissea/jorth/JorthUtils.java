package com.lapissea.jorth;

import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.stream.Collectors;

public class JorthUtils{
	
	private static Type extractFromVarType(TypeVariable<?> c){
		var bounds = c.getBounds();
		if(bounds.length == 1){
			return bounds[0];
		}
		throw new NotImplementedException(TextUtil.toString("wut? ", bounds));
	}
	
	public static String toJorthGeneric(Type type){
		return switch(type){
			case TypeVariable<?> c -> toJorthGeneric(extractFromVarType(c));
			case GenericArrayType arr -> toJorthGeneric(arr.getGenericComponentType()) + " array";
			case WildcardType wild -> {
				var    lower  = wild.getLowerBounds();
				var    bounds = lower;
				String typ;
				
				if(lower.length>0){
					typ = "super";
				}else{
					var upper = wild.getUpperBounds();
					if(upper.length>0 && !upper[0].equals(Object.class)){
						bounds = upper;
						typ = "extends";
					}else yield "?";
				}
				
				yield "? " + typ + " " + (
					bounds.length == 1?
					toJorthGeneric(bounds[0]) :
					Arrays.stream(bounds).map(JorthUtils::toJorthGeneric)
					      .collect(Collectors.joining(" ", "[", "]"))
				);
			}
			case ParameterizedType t -> {
				var sb   = new StringBuilder(toJorthGeneric(t.getRawType()));
				var args = t.getActualTypeArguments();
				if(args.length>0){
					sb.append('<');
					for(Type arg : args){
						sb.append(toJorthGeneric(arg)).append(' ');
					}
					sb.append('>');
				}
				yield sb.toString();
			}
			case Class<?> cls -> {
				StringBuilder sb   = new StringBuilder();
				var           dims = 0;
				while(cls.isArray()){
					dims++;
					cls = cls.componentType();
				}
				sb.append(cls.getName());
				
				sb.append(" array".repeat(dims));
				yield sb.toString();
			}
			default -> throw new IllegalArgumentException(type.getClass().getName());
		};
	}
	
}
