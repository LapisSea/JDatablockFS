package com.lapissea.jorth.lang.info;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassInfo;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.TypeSource;
import com.lapissea.jorth.lang.type.Visibility;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public interface FunctionInfo{
	
	record Signature(String name, List<GenericType> args){
		public Signature(String name){
			this(name, List.of());
		}
		public Signature(String name, List<GenericType> args){
			this.name = name;
			var argTmp = List.copyOf(args);
			var copy   = false;
			for(int i = 0; i<argTmp.size(); i++){
				if(argTmp.get(i).args().isEmpty()) continue;
				if(!copy) argTmp = new ArrayList<>(argTmp);
				copy = true;
				argTmp.set(i, argTmp.get(i).withoutArgs());
			}
			if(copy) argTmp = List.copyOf(args);
			this.args = argTmp;
		}
		@Override
		public String toString(){
			return "name(" + args.stream().map(GenericType::toString).collect(Collectors.joining(", ")) + ")";
		}
	}
	
	class OfMethod implements FunctionInfo{
		
		private final Method            method;
		private final ClassInfo         owner;
		private final GenericType       returnType;
		private final List<GenericType> args;
		
		public OfMethod(TypeSource source, Method method) throws MalformedJorth{
			this.method = method;
			owner = source.byName(ClassName.of(method.getDeclaringClass()));
			returnType = GenericType.of(method.getGenericReturnType());
			
			var tArgs = method.getGenericParameterTypes();
			var args  = new ArrayList<GenericType>(tArgs.length);
			for(Type tArg : tArgs){
				args.add(GenericType.of(tArg));
			}
			this.args = args;
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
			if(Modifier.isPublic(method.getModifiers())){
				return Visibility.PUBLIC;
			}
			if(Modifier.isProtected(method.getModifiers())){
				return Visibility.PROTECTED;
			}
			return Visibility.PRIVATE;
		}
		@Override
		public ClassInfo owner(){
			return owner;
		}
		@Override
		public String name(){
			return method.getName();
		}
		@Override
		public GenericType returnType(){
			return returnType;
		}
		
		@Override
		public List<GenericType> argumentTypes(){
			return args;
		}
	}
	
	class OfConstructor implements FunctionInfo{
		
		private final Constructor<?>    ctor;
		private final ClassInfo         owner;
		private final List<GenericType> args;
		
		public OfConstructor(TypeSource source, Constructor<?> ctor) throws MalformedJorth{
			this.ctor = ctor;
			owner = source.byName(ClassName.of(ctor.getDeclaringClass()));
			
			var tArgs = ctor.getGenericParameterTypes();
			var args  = new ArrayList<GenericType>(tArgs.length);
			for(Type tArg : tArgs){
				args.add(GenericType.of(tArg));
			}
			this.args = args;
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
			if(Modifier.isPublic(ctor.getModifiers())){
				return Visibility.PRIVATE;
			}
			if(Modifier.isProtected(ctor.getModifiers())){
				return Visibility.PROTECTED;
			}
			return Visibility.PRIVATE;
		}
		@Override
		public ClassInfo owner(){
			return owner;
		}
		@Override
		public String name(){
			return "<init>";
		}
		@Override
		public GenericType returnType(){
			return null;
		}
		
		@Override
		public List<GenericType> argumentTypes(){
			return args;
		}
	}
	
	static FunctionInfo of(TypeSource source, Method method) throws MalformedJorth{
		return new OfMethod(source, method);
	}
	static FunctionInfo of(TypeSource source, Constructor<?> method) throws MalformedJorth{
		return new OfConstructor(source, method);
	}
	
	boolean isStatic();
	boolean isFinal();
	Visibility visibility();
	
	ClassInfo owner();
	String name();
	GenericType returnType();
	List<GenericType> argumentTypes();
}
