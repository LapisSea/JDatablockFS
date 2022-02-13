package com.lapissea.jorth;

import com.lapissea.jorth.lang.BytecodeUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;

public class TestUtils{
	
	static Class<?> generateAndLoadInstanceSimple(String className, UnsafeConsumer<JorthWriter, MalformedJorthException> generator) throws ReflectiveOperationException{
		return generateAndLoadInstance(className, writer->{
			
			//Define constants / imports
			writer.write("#TOKEN(0) Str define", String.class.getName());
			writer.write("#TOKEN(0) Obj define", Object.class.getName());
			
			//define class
			writer.write(
				"""
					public visibility
					#TOKEN(0) class start
					""",
				className
			);
			
			generator.accept(writer);
		});
	}
	
	static Class<?> generateAndLoadInstance(String className, UnsafeConsumer<JorthWriter, MalformedJorthException> generator) throws ReflectiveOperationException{
		
		var loader=new ClassLoader(TestUtils.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				
				if(name.equals(className)){
					var jorth=new JorthCompiler(this);
					try{
						
						try(var writer=jorth.writeCode()){
							generator.accept(writer);
						}
						
						var byt=jorth.classBytecode();
						BytecodeUtils.printClass(byt);
						
						return defineClass(name, ByteBuffer.wrap(byt), null);
					}catch(MalformedJorthException e){
						throw new RuntimeException("Failed to generate class "+className, e);
					}
				}
				return super.findClass(name);
			}
		};
		
		var cls=Class.forName(className, true, loader);
		assert cls.getName().equals(className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		return cls;
	}
}
