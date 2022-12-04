package com.lapissea.jorth.v2;

import com.lapissea.jorth.lang.BytecodeUtils;
import com.lapissea.util.LogUtil;

import java.lang.invoke.MethodHandles;

public class JorthTmp{
	
	String foo;
	
	static{
		LogUtil.println("hi");
	}
	
	JorthTmp(){
		foo="123";
	}
	
	public static void main(String[] args) throws Throwable{
		LogUtil.println("Starting");
		Thread.ofVirtual().start(()->{});
		long t=System.currentTimeMillis();
		
		var jorth=new Jorth(null, true);
		jorth.addImport(String.class);
		jorth.addImport(StringBuilder.class);
		
		var writer=jorth.writer();
		
		writer.write(
			"""
				visibility public
				class com.lapissea.jorth.v2.FooBar start
					visibility public
					field foo #String
					
					visibility public
					function getFoo
						returns #String
					start
						get this foo
					end
					
					function <init> start
						super
						'hello world'
						set this foo
					end
					
					visibility public
					function toString
						returns #String
					start
						new #StringBuilder
						'{foo: '
						call append
						
						get this foo
						call append
						
						call toString
					end
				end
				""");
		
		var file=jorth.getClassFile("com.lapissea.jorth.v2.FooBar");
		
		LogUtil.println();
		LogUtil.println(System.currentTimeMillis()-t);
		BytecodeUtils.printClass(file);
		var cls=MethodHandles.lookup().defineClass(file);
		LogUtil.println();
		LogUtil.println(cls);
		
		var inst=cls.getConstructor().newInstance();
		LogUtil.println(inst);
		
		LogUtil.println(cls.getMethod("getFoo").invoke(inst));
	}
	
}
