package com.lapissea.dfs.type;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.utils.IterablePPs;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.List;

public final class GenericContext implements Stringify{
	
	public static GenericContext of(Class<?> type, Type actual){
		return new GenericContext(type, actual);
	}
	
	private static final GenericContext[] RAW_CACHE = new GenericContext[16];
	private static       int              cacheRollIndex;
	
	private static GenericContext getRawCache(Class<?> type){
		for(var gen : RAW_CACHE){
			if(gen == null || gen.owner != type) continue;
			return gen;
		}
		return null;
	}
	
	private static void putRawCache(GenericContext type){
		for(int i = 0; i<RAW_CACHE.length; i++){
			var lambSauce = RAW_CACHE[i];
			if(lambSauce == null){
				RAW_CACHE[i] = type;
				return;
			}
		}
		var i = cacheRollIndex++;
		if(i>=RAW_CACHE.length) i = cacheRollIndex = 0;
		RAW_CACHE[i] = type;
	}
	
	public static GenericContext of(Class<?> owner){
		var cached = getRawCache(owner);
		if(cached != null) return cached;
		return fromRaw(owner);
	}
	
	private static GenericContext fromRaw(Class<?> owner){
		var parms = owner.getTypeParameters();
		List<Type> args = switch(parms.length){
			case 0 -> List.of();
			case 1 -> List.of(parms[0].getBounds()[0]);
			case 2 -> List.of(parms[0].getBounds()[0], parms[1].getBounds()[0]);
			default -> {
				var rawArgs = new Type[parms.length];
				for(int i = 0; i<parms.length; i++){
					rawArgs[i] = parms[i].getBounds()[0];
				}
				yield List.of(rawArgs);
			}
		};
		var parm = SyntheticParameterizedType.of(owner, args);
		var ctx  = new GenericContext(owner, parm);
		putRawCache(ctx);
		return ctx;
	}
	
	private final String[]   typeNames;
	private final char[]     typeNamesC;
	private final List<Type> actualTypes;
	
	public final Class<?> owner;
	
	private GenericContext(Class<?> owner, Type actual){
		this.owner = owner;
		var params = owner.getTypeParameters();
		actualTypes = switch(actual){
			case SyntheticParameterizedType syn -> syn.getActualTypeArgumentsList();
			case ParameterizedType parm -> Arrays.asList(parm.getActualTypeArguments());
			default -> List.of();
		};
		
		String[] typeNames  = null;
		char[]   typeNamesC = null;
		for(int i = 0; i<params.length; i++){
			var name = params[i].getName();
			if(name.length() == 1){
				if(typeNamesC == null) typeNamesC = new char[params.length];
				typeNamesC[i] = name.charAt(0);
				continue;
			}
			if(typeNames == null) typeNames = new String[params.length];
			typeNames[i] = name;
		}
		this.typeNames = typeNames;
		this.typeNamesC = typeNamesC;
	}
	
	@Override
	public String toString(){
		return Utils.typeToHuman(owner) +
		       IterablePPs.rangeMap(0, actualTypes.size(), i -> strName(i) + "=" + Utils.typeToHuman(actualTypes.get(i)))
		                  .joinAsStr(", ", "<", ">");
	}
	@Override
	public String toShortString(){
		return Utils.typeToHuman(owner) +
		       IterablePPs.rangeMap(0, actualTypes.size(), i -> strName(i) + "=" + Utils.typeToHuman(actualTypes.get(i)))
		                  .joinAsStr(", ", "<", ">");
	}
	private String strName(int i){
		if(typeNames != null){
			var name = typeNames[i];
			if(name != null) return name;
		}
		return "" + typeNamesC[i];
	}
	
	private Type getType(String name){
		find:
		if(name.length() == 1){
			if(typeNamesC == null) break find;
			var c = name.charAt(0);
			if(c == '\0') break find;
			for(int i = 0; i<typeNamesC.length; i++){
				if(typeNamesC[i] == c){
					return actualTypes.get(i);
				}
			}
		}else{
			if(typeNames == null) break find;
			for(int i = 0; i<typeNames.length; i++){
				if(name.equals(typeNames[i])){
					return actualTypes.get(i);
				}
			}
		}
		throw new RuntimeException(name + " is not present");
	}
	
	private Type resolveVarType(TypeVariable<?> var){
		var realType = getType(var.getName());
		for(Type bound : var.getBounds()){
			if(!Utils.genericInstanceOf(realType, bound)){
				throw new ClassCastException(realType + " is not valid for " + bound);
			}
		}
		return realType;
	}
	
	
	public Type resolveType(Type genericType){
		try{
			return switch(genericType){
				case null -> null;
				case ParameterizedType parmType -> {
					var args  = parmType.getActualTypeArguments();
					var dirty = false;
					for(int i = 0; i<args.length; i++){
						switch(args[i]){
							case TypeVariable<?> var -> {
								args[i] = resolveVarType(var);
								dirty = true;
							}
							case ParameterizedType typ -> {
								var resolved = resolveType(typ);
								if(typ != resolved){
									args[i] = resolved;
									dirty = true;
								}
							}
							default -> { }
						}
					}
					if(dirty){
						yield SyntheticParameterizedType.of(parmType.getOwnerType(), (Class<?>)parmType.getRawType(), List.of(args));
					}
					yield parmType;
				}
				default -> genericType;
			};
		}catch(Throwable e){
			throw new IllegalArgumentException("Failed to resolve " + genericType, e);
		}
	}
	
	public GenericContext argAsContext(String argName){
		var type = getType(argName);
		
		return switch(type){
			case ParameterizedType parm -> GenericContext.of((Class<?>)parm.getRawType(), type);
			case Class<?> raw -> GenericContext.of(raw);
			case TypeVariable<?> var -> {
				var bounds = var.getBounds();
				if(bounds.length != 1){
					throw new NotImplementedException("Multiple bounds not implemented: " + var);
				}
				yield new GenericContext(Utils.typeToRaw(bounds[0]), bounds[0]);
			}
			default -> throw new NotImplementedException(type.getClass().getName());
		};
	}
}
