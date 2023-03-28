package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.nio.ByteBuffer;

public class JorthTmp{
	
	public static void main(String[] args) throws Throwable{
		LogUtil.println("Starting");
		Thread.startVirtualThread(() -> { });

//		test1();
		test2();
	}
	
	private static void test1() throws Throwable{
		var cls = timedClass("com.lapissea.jorth.FooBar", writer -> {
			writer.write(
				"""
					private
					field foo #String
					
					public function fooGet
						returns #String
					start
						get this foo
					end
					
					function <init> start
						super
						'hello world'
						set this foo
					end
					
					public function toString
						returns #String
					start
						new #StringBuilder start end
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
		var cls = timedClass("com.lapissea.jorth.FooBar2", writer -> {
			writer.addImport(LogUtil.class);
			writer.write(
				"""
					public
					function if1
						arg val int
						returns #String
					start
						get #arg val 1 ==
						if start
							'is 1' return
						end
						
						'isn\\'t 1' return
					end
					
					public
					function throwHi
						returns int
					start
						static call #LogUtil println start 'Heyyy I\\'m about to throw' end
						new #RuntimeException start
							'Hi! :D'
						end throw
					end
					""");
		});
		
		var inst = cls.getConstructor().newInstance();
		
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 0));
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 1));
		LogUtil.println(cls.getMethod("if1", int.class).invoke(inst, 2));
		try{
			cls.getMethod("throwHi").invoke(inst);
			LogUtil.printlnEr(":(");
		}catch(ReflectiveOperationException e){
			e.getCause().printStackTrace();
			LogUtil.println(e.getCause().getMessage());
		}
	}
	
	static Class<?> timedClass(String name, UnsafeConsumer<CodeStream, MalformedJorth> write) throws MalformedJorth, IllegalAccessException{
		var file = makeFile(name, write);
		try{
			return Class.forName(name, true, new ClassLoader(JorthTmp.class.getClassLoader()){
				@Override
				protected Class<?> findClass(String n) throws ClassNotFoundException{
					if(n.equals(name)){
						return defineClass(name, ByteBuffer.wrap(file), null);
					}
					return super.findClass(n);
				}
			});
		}catch(ClassNotFoundException e){
			throw new RuntimeException(e);
		}
	}
	
	static byte[] makeFile(String name, UnsafeConsumer<CodeStream, MalformedJorth> write) throws MalformedJorth, IllegalAccessException{
		long    t     = System.currentTimeMillis();
		boolean print = true;
		var     jorth = new Jorth(null, null);
		
		try(var writer = jorth.writer()){
			writer.write(
				"""
					public class {!} start
					""", name);
			write.accept(writer);
			writer.write("end");
		}
		
		var file = jorth.getClassFile(name);
		var t2   = System.currentTimeMillis();
		if(print) LogUtil.println();
		LogUtil.println("Time:", t2 - t);
		if(print) BytecodeUtils.printClass(file);
		return file;
	}
}
