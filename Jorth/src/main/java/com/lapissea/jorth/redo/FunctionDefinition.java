package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.type.BaseType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public class FunctionDefinition{
	
	private record Arg(JType type, BaseType info, String name, int accessIndex){ }
	
	private final ClassDefinition owner;
	private       AccessSet       access;
	private       Visibility      visibility = Visibility.PUBLIC;
	
	private final String                     name;
	private final LinkedHashMap<String, Arg> args;
	private       JType                      returnType = GenericType.VOID;
	
	
	public FunctionDefinition(ClassDefinition owner, String name, List<ArgInfo> args, AccessSet access){
		this.owner = Objects.requireNonNull(owner);
		this.name = Objects.requireNonNull(name);
		this.access = Objects.requireNonNull(access);
		
		this.args = LinkedHashMap.newLinkedHashMap(args.size());
		
		int counter = access.isStatic()? 0 : 1;
		for(var arg : args){
			var info = arg.type().getBaseType();
			this.args.put(arg.name(), new Arg(arg.type(), info, arg.name(), counter));
			counter += info.slots;
		}
	}
	
	public FunctionDefinition returns(JType type){
		this.returnType = Objects.requireNonNull(type);
		return this;
	}
	
	public FunctionDefinition visibility(Visibility visibility){
		this.visibility = Objects.requireNonNull(visibility);
		return this;
	}
	public Visibility visibility(){
		return visibility;
	}
	public AccessSet getAccess(){
		return access;
	}
	public String getName(){
		return name;
	}
	public JType getReturnType(){
		return returnType;
	}
	
	public CodeBlock body(){
		throw new NotImplementedException();
	}
}
