package com.lapisseqa.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.Test;

public class JorthTests{
	
	public static void main(String[] args) throws ReflectiveOperationException{
		new JorthTests().dummyClass();
	}
	
	@Test
	void dummyClass() throws ReflectiveOperationException{
		
		var loader=new ClassLoader(getClass().getClassLoader()){
			@Override
			protected Class<?> findClass(String className) throws ClassNotFoundException{
				
				var jorth=new JorthCompiler();
				
				try(var writer=jorth.writeCode()){
					
					//Define constants / imports
					writer.write("#TOKEN(0) Str define", String.class.getName());
					
					//define class
					writer.write(
						"""
							[#TOKEN(0)] #TOKEN(1) extends
							public visibility
							#TOKEN(0) class start
							""",
						className,
						IOInstance.class.getName()
					);
					
					//TODO: add implicit empty constructor when none is defined
					writer.write("public visibility "+
					             "<init> function start "+
					
					             "function end");
					
					writer.write(
						"""
							Str returns
							toString function start
							'Ayyyy it works!'
							function end
							""",
						className);
					
					
				}catch(MalformedJorthException e){
					throw new RuntimeException("Failed to generate class "+className, e);
				}
				
				var byt=jorth.classBytecode();

//				LogUtil.println(className);
//				LogUtil.println(new String(byt.array()));
				return defineClass(className, byt, null);
			}
		};
		
		var cls=Class.forName("com.lapissea.TestClass", false, loader);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		
		var constr=cls.getConstructor();
		var inst  =constr.newInstance();
		var str   =inst.toString();
		LogUtil.println(str);
	}
	
}
