package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.info.FunctionInfo;
import com.lapissea.jorth.lang.info.FunctionInfo.Signature;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.stream.Stream;

public interface ClassInfo{
	
	class OfArray implements ClassInfo{
		
		private final TypeSource  source;
		private final GenericType component;
		
		private final ClassInfo base;
		
		public OfArray(TypeSource source, GenericType component) throws MalformedJorth{
			this.source = source;
			this.component = component;
			base = source.byType(GenericType.OBJECT);
		}
		
		@Override
		public FieldInfo getField(String name) throws MalformedJorth{
			throw NotImplementedException.infer();//TODO: implement OfArray.getField()
		}
		@Override
		public FunctionInfo getFunction(Signature signature) throws MalformedJorth{
			try{
				return base.getFunction(signature);
			}catch(MalformedJorth e){ }
			
			throw NotImplementedException.infer();//TODO: implement OfArray.getFunction()
		}
		@Override
		public ClassName name(){
			return component.raw();
		}
		@Override
		public ClassInfo superType() throws MalformedJorth{
			throw NotImplementedException.infer();//TODO: implement OfArray.superType()
		}
		@Override
		public ClassType type(){
			return ClassType.CLASS;
		}
		@Override
		public boolean isFinal(){
			throw NotImplementedException.infer();//TODO: implement OfArray.isFinal()
		}
	}
	
	class OfClass implements ClassInfo{
		
		private final TypeSource                   source;
		private final Class<?>                     clazz;
		private final Map<String, FieldInfo>       fields    = new HashMap<>();
		private final Map<Signature, FunctionInfo> functions = new HashMap<>();
		
		public OfClass(TypeSource source, Class<?> clazz){
			this.source = source;
			this.clazz = Objects.requireNonNull(clazz);
		}
		
		@Override
		public FieldInfo getField(String name) throws MalformedJorth{
			var f = fields.get(name);
			if(f != null) return f;
			try{
				f = FieldInfo.of(UtilL.getDeepDeclaredField(clazz, name));
			}catch(Throwable e){
				throw new MalformedJorth(name + " does not exist in " + clazz, e);
			}
			fields.put(name, f);
			return f;
		}
		
		@Override
		public FunctionInfo getFunction(Signature signature) throws MalformedJorth{
			var f = functions.get(signature);
			if(f != null) return f;
			
			var args = new Class<?>[signature.args().size()];
			for(int i = 0; i<signature.args().size(); i++){
				var arg = signature.args().get(i);
				var pt  = arg.getPrimitiveType().map(GenericType.BaseType::type);
				if(pt.isPresent()){
					args[i] = pt.get();
				}else{
					var name = arg.raw();
					try{
						args[i] = getLoader().loadClass(name.dotted());
					}catch(ClassNotFoundException e){
						throw new RuntimeException(e);
					}
				}
			}
			
			try{
				
				if(signature.name().equals("<init>")){
					return FunctionInfo.of(source, clazz.getDeclaredConstructor(args));
				}
				f = FunctionInfo.of(source, getDeepDeclaredMethod(clazz, signature.name(), args));
			}catch(ReflectiveOperationException e){
				throw new MalformedJorth(signature + " does not exist in " + clazz);
			}
			functions.put(signature, f);
			return f;
		}
		private ClassLoader getLoader(){
			var loader = clazz.getClassLoader();
			if(loader == null) loader = this.getClass().getClassLoader();
			return loader;
		}
		private static Method getDeepDeclaredMethod(@NotNull Class<?> type, String name, Class<?>[] args) throws ReflectiveOperationException{
			try{
				return type.getDeclaredMethod(name, args);
			}catch(ReflectiveOperationException e){
				if(type == Object.class) throw e;
				return getDeepDeclaredMethod(type.getSuperclass(), name, args);
			}
		}
		
		@Override
		public ClassName name(){
			return ClassName.of(clazz);
		}
		@Override
		public ClassInfo superType() throws MalformedJorth{
			if(clazz.isPrimitive()) return null;
			var sup = clazz.getSuperclass();
			if(sup == null) return null;
			return source.byName(ClassName.of(sup));
		}
		@Override
		public ClassType type(){
			if(clazz.isAnnotation()) return ClassType.ANNOTATION;
			if(clazz.isInterface()) return ClassType.INTERFACE;
			if(clazz.isEnum()) return ClassType.ENUM;
			return ClassType.CLASS;
		}
		@Override
		public boolean isFinal(){
			return Modifier.isFinal(clazz.getModifiers());
		}
	}
	
	FieldInfo getField(String name) throws MalformedJorth;
	FunctionInfo getFunction(Signature signature) throws MalformedJorth;
	
	ClassName name();
	ClassInfo superType() throws MalformedJorth;
	ClassType type();
	
	boolean isFinal();
}
