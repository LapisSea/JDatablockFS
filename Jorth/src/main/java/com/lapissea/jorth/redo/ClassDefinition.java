package com.lapissea.jorth.redo;

import com.lapissea.jorth.exceptions.IllegalClassState;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.NotImplementedException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClassDefinition{
	
	private ClassType                                       type;
	private Map<FunctionInfo.Signature, FunctionDefinition> functions = new LinkedHashMap<>();
	private Map<String, FieldDefinition>                    fields    = new LinkedHashMap<>();
	
	public ClassDefinition(){
	
	}
	
	
	public byte[] getClassFile(){
		throw new NotImplementedException();
	}
	
	public void start(ClassType type, AccessSet access, Visibility visibility){
		throw new NotImplementedException();
	}
	public FunctionDefinition staticInit(){
		return function("<clinit>", List.of(), AccessSet.STATIC).visibility(Visibility.PUBLIC);
	}
	
	public FunctionDefinition function(String name, List<ArgInfo> args, AccessSet access){
		var fn = functions.computeIfAbsent(new FunctionInfo.Signature(name, toJTypes(args)), __ -> {
			return new FunctionDefinition(this, name, args, access);
		});
		checkVal(fn.getAccess(), access);
		return fn;
	}
	private static void checkVal(Object old, Object newV){
		if(!old.equals(newV)){
			throw new IllegalClassState("Disagreement on function definition:" +
			                            "\n  Has:     " + old +
			                            "\n  But got: " + newV);
		}
	}
	
	private List<JType> toJTypes(List<ArgInfo> args){
		var res = new ArrayList<JType>(args.size());
		for(var arg : args){
			res.add(arg.type());
		}
		return res;
	}
	
	public FieldDefinition field(String name, JType type){
		return fields.computeIfAbsent(name, n -> new FieldDefinition(this, n, type));
	}
}
