package com.lapissea.jorth;

import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;

import java.util.ArrayList;

public class FnArgs extends ArrayList<ArgInfo>{
	
	
	public FnArgs arg(Class<?> typ, String name){
		return arg(GenericType.of(typ), name);
	}
	public FnArgs arg(JType typ, String name){
		add(new ArgInfo(typ, name));
		return this;
	}
	public FnArgs args(JType typ, String... names){
		ensureCapacity(this.size() + names.length);
		for(String name : names){
			add(new ArgInfo(typ, name));
		}
		return this;
	}
}
