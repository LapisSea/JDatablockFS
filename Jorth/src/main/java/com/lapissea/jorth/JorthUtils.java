package com.lapissea.jorth;

import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;

import java.lang.reflect.*;

public class JorthUtils{
	
	private static Type extractFromVarType(TypeVariable<?> c){
		var bounds = c.getBounds();
		if(bounds.length == 1){
			var typ = bounds[0];
			return typ;
		}
		throw new NotImplementedException(TextUtil.toString("wut? ", bounds));
	}
	
	public static String toJorthGeneric(Type type){
		return switch(type){
			case TypeVariable<?> c -> toJorthGeneric(extractFromVarType(c));
			case GenericArrayType arr -> toJorthGeneric(arr.getGenericComponentType()) + " array";
			case WildcardType wild -> throw new NotImplementedException("Implement wildcard handling");//TODO
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