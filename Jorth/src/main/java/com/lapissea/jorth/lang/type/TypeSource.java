package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public interface TypeSource{
	
	class Of2 implements TypeSource{
		private final TypeSource a;
		private final TypeSource b;
		
		public Of2(TypeSource a, TypeSource b){
			this.a = a;
			this.b = b;
		}
		
		@Override
		public Optional<ClassInfo> maybeByType(GenericType type){
			var result = a.maybeByType(type);
			if(result.isPresent()) return result;
			return b.maybeByType(type);
		}
	}
	
	class OfClassLoader implements TypeSource{
		private final ClassLoader classLoader;
		
		private final Map<String, Optional<ClassInfo>> cache = new HashMap<>();
		
		public OfClassLoader(ClassLoader classLoader){
			this.classLoader = classLoader;
		}
		
		@Override
		public Optional<ClassInfo> maybeByType(GenericType type){
			var name   = type.raw().dotted();
			var cached = cache.get(name);
			if(cached != null) return cached;
			
			var t = maybeByType0(type);
			cache.put(name, t);
			return t;
		}
		private Optional<ClassInfo> maybeByType0(GenericType type){
			
			var primitive = type.getPrimitiveType();
			if(primitive.isPresent()){
				return Optional.of(new ClassInfo.OfClass(this, primitive.get().type()));
			}
			
			if(type.dims()>0){
				try{
					return Optional.of(new ClassInfo.OfArray(this, type.withDims(type.dims() - 1)));
				}catch(MalformedJorth e){
					throw new RuntimeException(e);
				}
			}
			
			try{
				
				var name = type.raw().dotted();
				var cls  = Class.forName(name, false, classLoader);
				return Optional.of(new ClassInfo.OfClass(this, cls));
			}catch(ClassNotFoundException e){
				return Optional.empty();
			}
		}
	}
	
	static TypeSource concat(TypeSource a, TypeSource b){
		return new Of2(a, b);
	}
	
	static TypeSource of(ClassLoader classLoader){
		return new OfClassLoader(classLoader);
	}
	
	Optional<ClassInfo> maybeByType(GenericType type);
	
	default Optional<ClassInfo> maybeByName(ClassName name){
		return maybeByType(new GenericType(name));
	}
	default ClassInfo byName(ClassName name) throws MalformedJorth{
		return byType(new GenericType(name));
	}
	default ClassInfo byType(GenericType type) throws MalformedJorth{
		if(!type.args().isEmpty()) type = type.withoutArgs();
		var opt = maybeByType(type);
		if(opt.isEmpty()){
			throw new MalformedJorth(type + " could not be found");
		}
		return opt.get();
	}
	
	default void validateType(ClassName type) throws MalformedJorth{ byName(type); }
	default void validateType(JType jType) throws MalformedJorth{
		switch(jType){
			case GenericType type -> {
				byName(type.raw());
				for(var arg : type.args()){
					validateType(arg);
				}
			}
			case JType.Wildcard wild -> {
				for(var type : wild.lower()){
					validateType(type);
				}
				for(var type : wild.upper()){
					validateType(type);
				}
			}
		}
	}
}
