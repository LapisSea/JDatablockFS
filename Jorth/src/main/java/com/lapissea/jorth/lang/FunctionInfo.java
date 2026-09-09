package com.lapissea.jorth.lang;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public interface FunctionInfo{
	
	record Signature(String name, List<JType> args){
		public Signature(String name){
			this(name, List.of());
		}
		public Signature(String name, List<JType> args){
			this.name = name;
			List<JType> argTmp = List.copyOf(args);
			var         copy   = false;
			for(int i = 0; i<argTmp.size(); i++){
				if(!argTmp.get(i).hasArgs()) continue;
				if(!copy) argTmp = new ArrayList<>(argTmp);
				copy = true;
				argTmp.set(i, argTmp.get(i).withoutArgs());
			}
			if(copy) argTmp = List.copyOf(argTmp);
			this.args = argTmp;
		}
		@Override
		public String toString(){
			return name + "(" + args.stream().map(Object::toString).collect(Collectors.joining(", ")) + ")";
		}
	}
	
	class OfMethod implements FunctionInfo{
		
		private final Method      method;
		private final ClassInfo   owner;
		private final JType       returnType;
		private final List<JType> args;
		
		public OfMethod(TypeSource source, Method method){
			this.method = method;
			try{
				owner = source.byName(ClassName.of(method.getDeclaringClass()));
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
			returnType = method.getGenericReturnType() == void.class? null : JType.of(method.getGenericReturnType());
			
			var tArgs = method.getGenericParameterTypes();
			var args  = new ArrayList<JType>(tArgs.length);
			for(Type tArg : tArgs){
				args.add(JType.of(tArg));
			}
			this.args = args;
		}
		
		@Override
		public boolean isVarargs(){
			return method.isVarArgs();
		}
		
		@Override
		public boolean isStatic(){
			return Modifier.isStatic(method.getModifiers());
		}
		@Override
		public boolean isFinal(){
			return Modifier.isFinal(method.getModifiers());
		}
		@Override
		public Visibility visibility(){
			return Visibility.ofModifiers(method.getModifiers());
		}
		@Override
		public ClassInfo ownerInfo(){
			return owner;
		}
		@Override
		public String name(){
			return method.getName();
		}
		@Override
		public JType returnType(){
			return returnType;
		}
		
		@Override
		public List<JType> argumentTypes(){
			return args;
		}
		@Override
		public List<ClassName> getThrownExceptions(){
			return Arrays.stream(method.getExceptionTypes()).map(ClassName::of).toList();
		}
		private Object  defaultEnumValue;
		private boolean defaultEnumValueCached;
		
		@Override
		public Object defaultEnumValue(){
			if(!defaultEnumValueCached){
				defaultEnumValue = method.getDefaultValue();
				defaultEnumValueCached = true;
			}
			return defaultEnumValue;
		}
		@Override
		public String toString(){
			return method.toString();
		}
	}
	
	class OfConstructor implements FunctionInfo{
		
		private final Constructor<?> ctor;
		private final ClassInfo      owner;
		private final List<JType>    args;
		
		public OfConstructor(TypeSource source, Constructor<?> ctor){
			this.ctor = ctor;
			try{
				owner = source.byName(ClassName.of(ctor.getDeclaringClass()));
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
			
			var tArgs = ctor.getGenericParameterTypes();
			var args  = new ArrayList<JType>(tArgs.length);
			for(Type tArg : tArgs){
				args.add(JType.of(tArg));
			}
			this.args = args;
		}
		
		@Override
		public boolean isVarargs(){
			return ctor.isVarArgs();
		}
		@Override
		public boolean isStatic(){
			return Modifier.isStatic(ctor.getModifiers());
		}
		@Override
		public boolean isFinal(){
			return Modifier.isFinal(ctor.getModifiers());
		}
		@Override
		public Visibility visibility(){
			return Visibility.ofModifiers(ctor.getModifiers());
		}
		@Override
		public ClassInfo ownerInfo(){
			return owner;
		}
		@Override
		public String name(){
			return "<init>";
		}
		@Override
		public JType returnType(){
			return null;
		}
		
		@Override
		public List<JType> argumentTypes(){
			return args;
		}
		@Override
		public List<ClassName> getThrownExceptions(){
			return Arrays.stream(ctor.getExceptionTypes()).map(ClassName::of).toList();
		}
		@Override
		public Object defaultEnumValue(){
			return null;
		}
		@Override
		public String toString(){
			return ctor.toString();
		}
	}
	
	static FunctionInfo of(TypeSource source, Method method){
		return new OfMethod(source, method);
	}
	static FunctionInfo of(TypeSource source, Constructor<?> method){
		return new OfConstructor(source, method);
	}
	
	boolean isVarargs();
	
	boolean isStatic();
	boolean isFinal();
	Visibility visibility();
	
	ClassInfo ownerInfo();
	String name();
	JType returnType();
	List<JType> argumentTypes();
	List<ClassName> getThrownExceptions();
	Object defaultEnumValue();
	
	default Signature makeSignature(){
		return new Signature(name(), argumentTypes());
	}
}
