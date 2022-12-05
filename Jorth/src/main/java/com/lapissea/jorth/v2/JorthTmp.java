package com.lapissea.jorth.v2;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.jorth.lang.BytecodeUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.invoke.MethodHandles;

public class JorthTmp{
	
	public static void main(String[] args) throws Throwable{
		LogUtil.println("Starting");
		Thread.ofVirtual().start(() -> { });
		
		test2();
	}
	
	private static void test1() throws Throwable{
		var cls = timedClass("com.lapissea.jorth.v2.FooBar", writer -> {
			writer.write(
				"""
					visibility private
					field foo #String
					
					visibility public
					function fooGet
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
						call append start '{foo: ' end
						
						call append start
							get this foo
						end
						call append start '}' end
						call toString start end
					end
					""");
		});
		
		
		LogUtil.println();
		LogUtil.println(cls);
		
		var inst = cls.getConstructor().newInstance();
		LogUtil.println(inst);
		
		LogUtil.println(cls.getMethod("fooGet").invoke(inst));
	}
	
	private static void test2() throws Throwable{
		var cls = timedClass("com.lapissea.jorth.v2.FooBar2", writer -> {
			writer.write(
				"""
					visibility public
					function if1
						arg val int
						returns #String
					start
						get #arg val 1 equals
						if start
							'is 1' return
						end
						
						'isn\\'t 1' return
					end
					
					visibility public
					function throwHi
						returns int
					start
						new #RuntimeException start
							'Hi! :D'
						end throw
					end
					""");
		});
		
		
		LogUtil.println();
		LogUtil.println(cls);
		
		var inst = cls.getConstructor().newInstance();
		LogUtil.println(inst);
		
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 0));
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 1));
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 2));
		try{
			cls.getMethod("throwHi").invoke(inst);
			LogUtil.printlnEr(":(");
		}catch(ReflectiveOperationException e){
			LogUtil.println(e.getCause().getMessage());
		}
	}
	
	static Class<?> timedClass(String name, UnsafeConsumer<CodeStream, MalformedJorthException> write) throws MalformedJorthException, IllegalAccessException{
		long t = System.currentTimeMillis();
		
		var jorth = new Jorth(null, true);
		
		var writer = jorth.writer();
		
		writer.write(
			"""
				visibility public
				class {!} start
				""", name);
		write.accept(writer);
		writer.write("end");
		
		var file = jorth.getClassFile(name);
		
		LogUtil.println();
		LogUtil.println(System.currentTimeMillis() - t);
		BytecodeUtils.printClass(file);
		return MethodHandles.lookup().defineClass(file);
	}
}
