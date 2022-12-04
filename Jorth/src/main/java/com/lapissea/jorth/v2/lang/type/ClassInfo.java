package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.v2.lang.ClassName;
import com.lapissea.jorth.v2.lang.info.FunctionInfo;
import com.lapissea.jorth.v2.lang.info.FunctionInfo.Signature;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public interface ClassInfo{
	
	class OfClass implements ClassInfo{
		
		private final TypeSource                   source;
		private final Class<?>                     clazz;
		private final Map<String, FieldInfo>       fields   =new HashMap<>();
		private final Map<Signature, FunctionInfo> functions=new HashMap<>();
		
		public OfClass(TypeSource source, Class<?> clazz){
			this.source=source;
			this.clazz=clazz;
		}
		
		@Override
		public FieldInfo getField(String name) throws MalformedJorthException{
			var f=fields.get(name);
			if(f!=null) return f;
			try{
				f=FieldInfo.of(UtilL.getDeepDeclaredField(clazz, name));
			}catch(ReflectiveOperationException e){
				throw new MalformedJorthException(name+" does not exist in "+clazz, e);
			}
			fields.put(name, f);
			return f;
		}
		
		@Override
		public FunctionInfo getFunction(Signature signature) throws MalformedJorthException{
			var f=functions.get(signature);
			if(f!=null) return f;
			try{
				
				var args=new Class<?>[signature.args().size()];
				for(int i=0;i<signature.args().size();i++){
					var name=signature.args().get(i).raw();
					args[i]=clazz.getClassLoader().loadClass(name.dotted());
				}
				
				if(signature.name().equals("<init>")){
					return FunctionInfo.of(source, clazz.getConstructor(args));
				}
				f=FunctionInfo.of(source, getDeepDeclaredMethod(clazz, signature.name(), args));
			}catch(ReflectiveOperationException e){
				throw new MalformedJorthException(signature+" does not exist in "+clazz, e);
			}
			functions.put(signature, f);
			return f;
		}
		private static Method getDeepDeclaredMethod(@NotNull Class<?> type, String name, Class<?>[] args) throws ReflectiveOperationException{
			try{
				return type.getDeclaredMethod(name, args);
			}catch(ReflectiveOperationException e){
				if(type==Object.class) throw e;
				return getDeepDeclaredMethod(type.getSuperclass(), name, args);
			}
		}
		
		@Override
		public ClassName name(){
			return ClassName.of(clazz);
		}
		@Override
		public ClassInfo superType() throws MalformedJorthException{
			if(clazz.isPrimitive()) return null;
			var sup=clazz.getSuperclass();
			if(sup==null) return null;
			return source.byName(ClassName.of(sup));
		}
		@Override
		public ClassType type(){
			if(clazz.isInterface()) return ClassType.INTERFACE;
			if(clazz.isEnum()) return ClassType.ENUM;
			return ClassType.CLASS;
		}
		@Override
		public boolean isFinal(){
			return Modifier.isFinal(clazz.getModifiers());
		}
	}
	
	FieldInfo getField(String name) throws MalformedJorthException;
	FunctionInfo getFunction(Signature signature) throws MalformedJorthException;
	
	ClassName name();
	ClassInfo superType() throws MalformedJorthException;
	ClassType type();
	
	boolean isFinal();
}
