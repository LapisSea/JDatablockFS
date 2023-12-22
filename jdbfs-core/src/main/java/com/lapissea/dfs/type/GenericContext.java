package com.lapissea.dfs.type;

import com.lapissea.dfs.SyntheticParameterizedType;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.util.NotImplementedException;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface GenericContext extends Stringify{
	
	static GenericContext of(Class<?> type, Type actual){
		return new GenericContext.Deferred(() -> {
			return new GenericContext.TypeArgs(type, actual);
		});
	}
	
	final class TypeArgs implements GenericContext, Stringify{
		private final String[] typeNames;
		private final char[]   typeNamesC;
		private final Type[]   actualTypes;
		private final Class<?> type;
		
		public TypeArgs(Class<?> type, Type actual){
			this.type = type;
			var params     = type.getTypeParameters();
			var actualArgs = actual instanceof ParameterizedType p? p.getActualTypeArguments() : new Type[0];
			
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
			actualTypes = actualArgs;
		}
		
		@Override
		public Class<?> owner(){
			return type;
		}
		
		@Override
		public String toString(){
			return Utils.typeToHuman(type, false) +
			       IntStream.range(0, actualTypes.length)
			                .mapToObj(i -> strName(i) + "=" + Utils.typeToHuman(actualTypes[i], false))
			                .collect(Collectors.joining(", ", "<", ">"));
		}
		@Override
		public String toShortString(){
			return Utils.typeToHuman(type, true) +
			       IntStream.range(0, actualTypes.length)
			                .mapToObj(i -> strName(i) + "=" + Utils.typeToHuman(actualTypes[i], true))
			                .collect(Collectors.joining(", ", "<", ">"));
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
						return actualTypes[i];
					}
				}
			}else{
				if(typeNames == null) break find;
				for(int i = 0; i<typeNames.length; i++){
					if(name.equals(typeNames[i])){
						return actualTypes[i];
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
		
		
		@Override
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
		
		@Override
		public GenericContext argAsContext(String argName){
			var type = getType(argName);
			
			return switch(type){
				case ParameterizedType parm -> new TypeArgs((Class<?>)parm.getRawType(), type);
				case Class<?> raw -> {
					var parms = raw.getTypeParameters();
					
					var rawArgs = new Type[parms.length];
					for(int i = 0; i<parms.length; i++){
						rawArgs[i] = parms[i].getBounds()[0];
					}
					yield new TypeArgs(raw, SyntheticParameterizedType.of(raw, List.of(rawArgs)));
				}
				case TypeVariable<?> var -> {
					var bounds = var.getBounds();
					if(bounds.length != 1){
						throw new NotImplementedException("Multiple bounds not implemented: " + var);
					}
					yield new TypeArgs(Utils.typeToRaw(bounds[0]), bounds[0]);
				}
				default -> throw new NotImplementedException(type.getClass().getName());
			};
		}
	}
	
	final class Deferred implements GenericContext, Stringify{
		
		private GenericContext           data;
		private Supplier<GenericContext> dataSource;
		
		public Deferred(Supplier<GenericContext> dataSource){
			this.dataSource = dataSource;
		}
		
		private GenericContext getData(){
			if(data == null) fillData();
			return data;
		}
		
		private synchronized void fillData(){
			if(data != null) return;
			data = Objects.requireNonNull(dataSource.get());
			dataSource = null;
		}
		public boolean dataBaked(){
			return data != null;
		}
		public GenericContext actualData(){
			return getData();
		}
		
		
		@Override
		public Class<?> owner(){
			return getData().owner();
		}
		@Override
		public Type resolveType(Type genericType){
			return getData().resolveType(genericType);
		}
		@Override
		public GenericContext argAsContext(String argName){
			return getData().argAsContext(argName);
		}
		@Override
		public String toString(){
			if(data == null && GlobalConfig.RELEASE_MODE) return "DeferredCtx{?}";
			return getData().toString();
		}
		@Override
		public String toShortString(){
			if(data == null && GlobalConfig.RELEASE_MODE) return "DeferredCtx";
			return getData().toShortString();
		}
	}
	
	Class<?> owner();
	
	Type resolveType(Type genericType);
	GenericContext argAsContext(String argName);
}
