package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorthException;
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
			var primitive = type.getPrimitiveType();
			if(primitive.isPresent()){
				return Optional.of(new ClassInfo.OfClass(this, primitive.get().type()));
			}
			var name   = type.raw().dotted();
			var cached = cache.get(name);
			if(cached != null) return cached;
			
			try{
				var cls = Class.forName(name, false, classLoader);
				for(int i = 0; i<type.dims(); i++){
					cls = cls.arrayType();
				}
				var result = Optional.<ClassInfo>of(new ClassInfo.OfClass(this, cls));
				cache.put(name, result);
				return result;
			}catch(ClassNotFoundException e){
				cache.put(name, Optional.empty());
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
	default ClassInfo byName(ClassName name) throws MalformedJorthException{
		return byType(new GenericType(name));
	}
	default ClassInfo byType(GenericType type) throws MalformedJorthException{
		var opt = maybeByType(type);
		if(opt.isEmpty()){
			throw new MalformedJorthException(type + " could not be found");
		}
		return opt.get();
	}
	
}
