package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.jorth.redo.AccessSet;
import com.lapissea.jorth.redo.ClassDefinition;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.StringJoiner;

public final class TestUtils{
	
	static Class<?> generateAndLoadInstanceSimple(
		String className,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		return generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					public class {!} start
					""",
				className
			);
			
			generator.accept(writer);
			writer.wEnd();
		}, cw -> {
			cw.start(ClassType.CLASS, AccessSet.DEFAULT, Visibility.PUBLIC);
			generator2.accept(cw);
		});
	}
	
	static Class<?> generateAndLoadInstance(
		String className,
		UnsafeConsumer<CodeStream, MalformedJorth> generator,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator2
	) throws ReflectiveOperationException{
		
		ClassDefinition cw;
		
		StringJoiner tokenStr = new StringJoiner(" ");
		var          jorth    = new Jorth(null, tokenStr::add);
		try{
			try(var writer = jorth.writer()){
				generator.accept(writer);
			}finally{
				LogUtil.println(tokenStr.toString());
			}
			
			cw = new ClassDefinition();
			generator2.accept(cw);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + className, e);
		}
		
		var classes = jorth.listClassFiles();
		
		var cwf = cw.getClassFile();
		if(!Arrays.equals(cwf, jorth.getClassFile(className))){
			throw new AssertionError("Class files not equal");
		}
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(classes.contains(name)){
					var byt = jorth.getClassFile(name);
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
	
	static Class<?> makeAndLoadInstance(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws ReflectiveOperationException{
		
		var cm = new ClassDefinition();
		try{
			generator.accept(cm);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate class " + className, e);
		}
		var clazz = cm.getClassFile();
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(className.equals(name)){
					return defineClass(name, ByteBuffer.wrap(clazz), null);
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
}
