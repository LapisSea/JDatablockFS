package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.util.TextUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public interface GenericContext{
	
	final class MapConstant implements GenericContext, Stringify{
		private final Map<String, Type> actualTypes;
		
		public MapConstant(Map<String, Type> actualTypes){
			this.actualTypes = Map.copyOf(actualTypes);
		}
		
		@Override
		public Type getTypeByName(String name){
			return actualTypes.get(name);
		}
		
		@Override
		public String toString(){
			return "Ctx" + TextUtil.toString(actualTypes);
		}
		@Override
		public String toShortString(){
			return "Ctx" + Utils.toShortString(actualTypes);
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
		
		public GenericContext actualData(){
			return data;
		}
		
		@Override
		public Type getTypeByName(String name){
			return getData().getTypeByName(name);
		}
		
		@Override
		public Type resolveVarType(TypeVariable<?> var){
			return getData().resolveVarType(var);
		}
		@Override
		public Type resolveType(Type genericType){
			return getData().resolveType(genericType);
		}
		@Override
		public String toString(){
			if(data == null) return "DeferredCtx{?}";
			return "Deferred" + data + "";
		}
		@Override
		public String toShortString(){
			if(data == null) return "DeferredCtx";
			return "Deferred" + data + "";
		}
	}
	
	Type getTypeByName(String name);
	
	private Type getTyp(String name){
		var type = getTypeByName(name);
		if(type != null) return type;
		
		throw new RuntimeException(name + " is not present");
	}
	
	default Type resolveVarType(TypeVariable<?> var){
		var realType = getTyp(var.getName());
		for(Type bound : var.getBounds()){
			if(!Utils.genericInstanceOf(realType, bound)){
				throw new ClassCastException(realType + " is not valid for " + bound);
			}
		}
		return realType;
	}
	
	default Type resolveType(Type genericType){
		try{
			return switch(genericType){
				case null -> null;
				case ParameterizedType parmType -> {
					var args  = parmType.getActualTypeArguments();
					var dirty = false;
					for(int i = 0; i<args.length; i++){
						if(args[i] instanceof TypeVariable<?> var){
							args[i] = resolveVarType(var);
							dirty = true;
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
}
