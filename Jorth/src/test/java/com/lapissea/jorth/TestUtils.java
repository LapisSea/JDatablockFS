package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class TestUtils{
	
	public static String autoName(){
		return "test.CL_" + StackWalker.getInstance().walk(
			s -> s.skip(1)
			      .findFirst()
			      .map(StackWalker.StackFrame::getMethodName)
			      .orElse("Unknown")
		);
	}
	
	static Class<?> generateAndLoadInstanceSimple(
		String className,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator
	) throws ReflectiveOperationException, MalformedJorth{
		return generateAndLoadInstance(className, cw -> {
			cw.name(ClassName.dotted(className));
			generator.accept(cw);
		});
	}
	
	static Class<?> generateAndLoadInstance(
		String className,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator
	) throws ReflectiveOperationException, MalformedJorth{
		
		ClassDefinition cw = new ClassDefinition(null);
		generator.accept(cw);
		byte[] byt = cw.getClassFile();
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(ClassName.dotted(name).equals(cw.name())){
					BytecodeUtils.printClass(byt);
					return defineClass(name, ByteBuffer.wrap(byt), null);
				}
				return super.findClass(name);
			}
		};
		
		var cls = Class.forName(className, true, loader);
		if(!cls.getName().equals(className)) throw new AssertionError(cls.getName() + " " + className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		return cls;
	}
	
	static Class<?> generateAndLoadInstanceMulti(
		List<String> classes,
		UnsafeBiConsumer<String, ClassDefinition, MalformedJorth> generator
	) throws ReflectiveOperationException{
		
		var classNames = classes.stream().map(ClassName::dotted).collect(Collectors.toSet());
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(classNames.contains(ClassName.slashed(name))){
					
					byte[] byt;
					try{
						ClassDefinition cw = new ClassDefinition(this);
						generator.accept(name, cw);
						byt = cw.getClassFile();
					}catch(Throwable e){
						e.printStackTrace();
						throw new RuntimeException(e);
					}
					
					BytecodeUtils.printClass(byt);
					
					return defineClass(name, ByteBuffer.wrap(byt), null);
				}
				return super.findClass(name);
			}
		};
		
		var className = classes.getFirst();
		
		var cls = Class.forName(className, true, loader);
		if(!cls.getName().equals(className)) throw new AssertionError(cls.getName() + " " + className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		return cls;
	}
	
	private static Type parm(Type raw, Type... parms){
		return new ParameterizedType(){
			@Override
			public Type[] getActualTypeArguments(){ return parms; }
			@Override
			public Type getRawType(){ return raw; }
			@Override
			public Type getOwnerType(){ return null; }
		};
	}
	
	public record TArg(String name, Class<?> type){ }
	
	public record InterfaceTemplate<T>(Type type, String functionName, List<TArg> args, Type returns){ }
	
	public static final InterfaceTemplate<IntUnaryOperator> INT_UNARY_OPERATOR = new InterfaceTemplate<>(
		IntUnaryOperator.class, "applyAsInt", List.of(new TArg("num", int.class)), int.class
	);
	public static final InterfaceTemplate<Supplier<String>> STRING_SUPPLIER    = new InterfaceTemplate<>(
		parm(Supplier.class, String.class), "get", List.of(), Object.class
	);
	public static final InterfaceTemplate<IntSupplier>      INT_SUPPLIER       = new InterfaceTemplate<>(
		IntSupplier.class, "getAsInt", List.of(), int.class
	);
	
	public static <T> T generateInterface(
		String name,
		InterfaceTemplate<T> template,
		UnsafeConsumer<CodeBlock, MalformedJorth> generator
	) throws ReflectiveOperationException, MalformedJorth{
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name)).implement(template.type);
			var fn = cd.function(template.functionName);
			for(var arg : template.args){
				fn.arg(arg.type(), arg.name());
			}
			if(template.returns != null){
				fn.returns(template.returns);
			}
			fn.annotation(Override.class);
			generator.accept(fn.body());
		});
		
		Object instO = cls.getConstructor().newInstance();
		Class<?> type = switch(template.type){
			case Class<?> c -> c;
			case ParameterizedType pt -> (Class<?>)pt.getRawType();
			default -> throw new IllegalStateException("Unexpected value: " + template.type);
		};
		//noinspection unchecked
		return (T)type.cast(instO);
	}
}
