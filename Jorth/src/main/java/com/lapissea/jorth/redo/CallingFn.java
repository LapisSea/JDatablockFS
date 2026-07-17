package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;

import java.util.ArrayList;
import java.util.List;

public final class CallingFn{
	public       String      name;
	public       GenericType returns;
	public final List<JType> args = new ArrayList<>();
	
	
	public CallingFn name(String name){
		this.name = name;
		return this;
	}
	
	public CallingFn arg(Class<?> type) { return arg(ClassName.of(type)); }
	public CallingFn arg(ClassName type){ return arg(GenericType.of(type)); }
	public CallingFn arg(GenericType type){
		args.add(type);
		return this;
	}
	
	public CallingFn returns(Class<?> type) { return returns(ClassName.of(type)); }
	public CallingFn returns(ClassName type){ return returns(GenericType.of(type)); }
	public CallingFn returns(GenericType type){
		returns = type;
		return this;
	}
}
