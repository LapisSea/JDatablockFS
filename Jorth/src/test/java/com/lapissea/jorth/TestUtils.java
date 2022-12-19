package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;
import java.util.StringJoiner;

public class TestUtils{
	
	static Class<?> generateAndLoadInstanceSimple(String className, UnsafeConsumer<CodeStream, MalformedJorth> generator) throws ReflectiveOperationException{
		return generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					public class {!} start
					""",
				className
			);
			
			generator.accept(writer);
			writer.write("end");
		});
	}
	
	static Class<?> generateAndLoadInstance(String className, UnsafeConsumer<CodeStream, MalformedJorth> generator) throws ReflectiveOperationException{
		
		var loader = new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				
				if(name.equals(className)){
					StringJoiner tokenStr = new StringJoiner(" ");
					var          jorth    = new Jorth(null, tokenStr::add);
					try{
						
						try(var writer = jorth.writer()){
							generator.accept(writer);
						}finally{
							LogUtil.println(tokenStr.toString());
						}
						
						var byt = jorth.getClassFile(className);
						BytecodeUtils.printClass(byt);
						
						return defineClass(name, ByteBuffer.wrap(byt), null);
					}catch(MalformedJorth e){
						throw new RuntimeException("Failed to generate class " + className, e);
					}
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
