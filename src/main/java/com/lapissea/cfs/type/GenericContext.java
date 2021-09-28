package com.lapissea.cfs.type;

import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.util.TextUtil;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public interface GenericContext{
	
	class MapConstant implements GenericContext{
		private final Map<String, Type> actualTypes;
		
		public MapConstant(Map<String, Type> actualTypes){
			this.actualTypes=new HashMap<>(actualTypes);
		}
		
		@Override
		public Type getTypeByName(String name){
			return actualTypes.get(name);
		}
		
		@Override
		public String toString(){
			return "Ctx"+TextUtil.toString(actualTypes);
		}
		public String toShortString(){
			return "Ctx"+TextUtil.toShortString(actualTypes);
		}
	}
	
	class Deferred implements GenericContext{
		
		private GenericContext           data;
		private Supplier<GenericContext> dataSource;
		
		public Deferred(Supplier<GenericContext> dataSource){
			this.dataSource=dataSource;
		}
		
		private GenericContext getData(){
			if(data==null) fillData();
			return data;
		}
		
		private synchronized void fillData(){
			if(data!=null) return;
			data=Objects.requireNonNull(dataSource.get());
			dataSource=null;
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
			if(data==null) return "DeferredCtx{?}";
			return "Deferred"+data+"";
		}
		public String toShortString(){
			if(data==null) return "DeferredCtx";
			return "Deferred"+data+"";
		}
	}
	
	Type getTypeByName(String name);
	
	private Type getTyp(String name){
		var type=getTypeByName(name);
		if(type!=null) return type;
		
		throw new RuntimeException(name+" is not present");
	}
	
	default Type resolveVarType(TypeVariable<?> var){
		var realType=getTyp(var.getName());
		for(Type bound : var.getBounds()){
			if(!Utils.genericInstanceOf(realType, bound)){
				throw new ClassCastException(realType+" is not valid for "+bound);
			}
		}
		return realType;
	}
	
	default Type resolveType(Type genericType){
		try{
			return switch(genericType){
				case null -> null;
				case ParameterizedType parmType -> {
					var args =parmType.getActualTypeArguments();
					var dirty=false;
					for(int i=0;i<args.length;i++){
						if(args[i] instanceof TypeVariable<?> var){
							args[i]=resolveVarType(var);
							dirty=true;
						}
					}
					if(dirty){
						yield new SyntheticParameterizedType(parmType.getOwnerType(), (Class<?>)parmType.getRawType(), args);
					}
					yield parmType;
				}
				default -> genericType;
			};
		}catch(Throwable e){
			throw new IllegalArgumentException("Failed to resolve "+genericType, e);
		}
	}
}
