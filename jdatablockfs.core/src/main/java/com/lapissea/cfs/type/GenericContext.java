package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.config.GlobalConfig;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.TextUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public interface GenericContext extends Stringify{
	
	final class TypeArgs implements GenericContext, Stringify{
		private final Map<String, Type> actualTypes;
		
		public TypeArgs(Class<?> type, Type actual){
			var params     = type.getTypeParameters();
			var actualArgs = actual instanceof ParameterizedType p? p.getActualTypeArguments() : new Type[0];
			
			actualTypes = HashMap.newHashMap(params.length);
			
			for(int i = 0; i<params.length; i++){
				actualTypes.put(params[i].getName(), actualArgs[i]);
			}
		}
		
		@Override
		public String toString(){
			return "Ctx" + TextUtil.toString(actualTypes);
		}
		@Override
		public String toShortString(){
			return "Ctx" + Utils.toShortString(actualTypes);
		}
		
		private Type getType(String name){
			var type = actualTypes.get(name);
			if(type != null) return type;
			
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
							yield SyntheticParameterizedType.of(parmType.getOwnerType(), (Class<?>)parmType.getRawType(), args);
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
					yield new TypeArgs(raw, SyntheticParameterizedType.of(raw, rawArgs));
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
	
	Type resolveType(Type genericType);
	GenericContext argAsContext(String argName);
}
