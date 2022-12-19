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
		public Stream<FunctionInfo> getFunctionsByName(String name){
			throw NotImplementedException.infer();//TODO: implement OfArray.getFunctionsByName()
		}
		@Override
		public Stream<? extends FunctionInfo> getFunctions(){
			throw NotImplementedException.infer();//TODO: implement OfArray.getFunctions()
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
		@Override
		public List<GenericType> interfaces(){
			return List.of();
		}
		@Override
		public List<Enum<?>> enumConstantNames(){
			return List.of();
		}
	}
	
	class OfClass implements ClassInfo{
		
		private final TypeSource                      source;
		private final Class<?>                        clazz;
		private final Map<String, FieldInfo>          fields          = new HashMap<>();
		private final Map<Signature, FunctionInfo>    functions       = new HashMap<>();
		private final Map<String, List<FunctionInfo>> functionsByName = new HashMap<>();
		
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
			{
				var f = functions.get(signature);
				if(f != null) return f;
			}
			
			var args = new Class<?>[signature.args().size()];
			for(int i = 0; i<signature.args().size(); i++){
				var arg = signature.args().get(i);
				var pt  = arg.getPrimitiveType().map(BaseType::type);
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
			}catch(NoSuchMethodException e){
				throw new MalformedJorth(signature + " does not exist in " + clazz);
			}
			
			Method method = null;
			try{
				method = getDeepDeclaredMethod(clazz, signature.name(), args);
			}catch(ReflectiveOperationException e){
				funs:
				for(var info : findByName(signature.name())){
					var fargs = info.argumentTypes();
					var sargs = signature.args();
					if(fargs.size() != sargs.size()){
						continue;
					}
					for(int i = 0; i<sargs.size(); i++){
						var s = sargs.get(i);
						var f = fargs.get(i);
						if(!s.instanceOf(source, f)){
							continue funs;
						}
					}
					return getFunction(new Signature(info.name(), info.argumentTypes()));
				}
			}
			if(method == null){
				throw new MalformedJorth(signature + " does not exist in " + clazz);
			}
			var f = FunctionInfo.of(source, method);
			functions.put(signature, f);
			return f;
		}
		@Override
		public Stream<? extends FunctionInfo> getFunctionsByName(String name){
			return functionsByName.computeIfAbsent(name, this::findByName).stream();
		}
		
		private boolean allFun;
		@Override
		public Stream<? extends FunctionInfo> getFunctions(){
			if(allFun) return functions.values().stream();
			
			Arrays.stream(clazz.getDeclaredConstructors())
			      .forEach(c -> functions.computeIfAbsent(
				      new Signature("<init>", Arrays.stream(c.getGenericParameterTypes()).map(GenericType::of).toList()),
				      m -> new FunctionInfo.OfConstructor(source, c)
			      ));
			
			var c = clazz;
			while(c != null){
				Arrays.stream(c.getDeclaredMethods())
				      .forEach(f -> {
					      var sig = new Signature(f.getName(), Arrays.stream(f.getGenericParameterTypes()).map(GenericType::of).toList());
					      functions.computeIfAbsent(sig, s -> FunctionInfo.of(source, f));
				      });
				c = c.getSuperclass();
			}
			
			return functions.values().stream();
		}
		
		private List<FunctionInfo> findByName(String name){
			var result = new ArrayList<FunctionInfo>();
			
			if(name.equals("<init>")){
				var ctors = clazz.getDeclaredConstructors();
				return Arrays.stream(ctors).<FunctionInfo>map(c -> new FunctionInfo.OfConstructor(source, c)).toList();
			}
			
			var c = clazz;
			while(c != null){
				Arrays.stream(c.getDeclaredMethods())
				      .filter(f -> f.getName().equals(name))
				      .forEach(f -> {
					      var sig  = new Signature(name, Arrays.stream(f.getGenericParameterTypes()).map(GenericType::of).toList());
					      var info = functions.computeIfAbsent(sig, s -> FunctionInfo.of(source, f));
					      result.add(info);
				      });
				c = c.getSuperclass();
			}
			
			return List.copyOf(result);
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
		
		private List<GenericType> interfaces;
		@Override
		public List<GenericType> interfaces(){
			if(interfaces == null){
				interfaces = Arrays.stream(clazz.getGenericInterfaces()).map(GenericType::of).toList();
			}
			return interfaces;
		}
		private List<Enum<?>> constants;
		
		@Override
		public List<Enum<?>> enumConstantNames(){
			if(constants == null){
				var c = (Enum<?>[])clazz.getEnumConstants();
				constants = c == null? List.of() : List.of(c);
			}
			return constants;
		}
	}
	
	FieldInfo getField(String name) throws MalformedJorth;
	FunctionInfo getFunction(Signature signature) throws MalformedJorth;
	Stream<? extends FunctionInfo> getFunctionsByName(String name);
	Stream<? extends FunctionInfo> getFunctions();
	
	ClassName name();
	ClassInfo superType() throws MalformedJorth;
	ClassType type();
	
	boolean isFinal();
	List<GenericType> interfaces();
	List<Enum<?>> enumConstantNames();
}
