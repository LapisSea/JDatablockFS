package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.v2.lang.ClassName;

import java.util.Optional;

public interface TypeSource{
	
	static TypeSource concat(TypeSource a, TypeSource b){
		return name->a.byName(name).or(()->b.byName(name));
	}
	
	static TypeSource of(ClassLoader classLoader){
		return name->{
			try{
				var cls=Class.forName(name.dotted(), false, classLoader);
				return Optional.of(new ClassInfo(){
				});
			}catch(ClassNotFoundException e){
				return Optional.empty();
			}
		};
	}
	
	Optional<ClassInfo> byName(ClassName name);
	
}
