package com.lapissea.jorth;

import com.lapissea.jorth.lang.BytecodeUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JorthTests{
	
	public static void main(String[] args) throws Throwable{
		new JorthTests().fieldClass();
	}
	
	public static class ISayHello{
		@Override
		public String toString(){
			return "Hello from ISayHello";
		}
	}
	
	@Test
	void mathClass() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
			//Define constants / imports
			writer.write("#TOKEN(0) Str define", String.class.getName());
			
			//define class
			writer.write(
				"""
					public visibility
					#TOKEN(0) class start
					""",
				className
			);
			
			writer.write(
				"""
					[$type] myMacro macro start
						static
						$type myArgumentName arg
						Str returns
						myFunctionName function start
							
							<arg> myArgumentName get
							' string concat works with $type'
							concat
							
						function end
					macro end
					""");
			writer.write(
				"""
					{java.lang.String $type} myMacro macro resolve
					{int $type}              myMacro macro resolve
					{float $type}            myMacro macro resolve
					{double $type}           myMacro macro resolve
					""");
		});
		
		var constr=cls.getConstructor();
		
		LogUtil.println(cls.getMethod("myFunctionName", String.class).invoke(null, "this is a test"));
		LogUtil.println(cls.getMethod("myFunctionName", int.class).invoke(null, 123));
		LogUtil.println(cls.getMethod("myFunctionName", float.class).invoke(null, 0.123F));
		LogUtil.println(cls.getMethod("myFunctionName", double.class).invoke(null, 0.345));
		
	}
	@Test
	void fieldClass() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
			//Define constants / imports
			writer.write("#TOKEN(0) Str define", String.class.getName());
			
			//define class
			writer.write(
				"""
					public visibility
					#TOKEN(0) class start
					""",
				className
			);
			
			writer.write(
				"""
					public visibility
					Str testString field
					"""
			);
			
			writer.write(
				"""
					Str returns
					toString function start
					this testString get
					function end
					""");
			
			writer.write(
				"""
					Str testString arg
					init function start
					
					<arg> testString get
					this testString set
					
					function end
					""");
		});
		
		var constr=cls.getConstructor();
		var inst  =constr.newInstance();
		
		var msg="this is a test";
		
		cls.getMethod("init", String.class).invoke(inst, msg);
		
		var str=inst.toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(msg, str);
	}
	
	@Test
	void overrideClass() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
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
				ISayHello.class.getName()
			);
		});
		
		var constr=cls.getConstructor();
		var inst  =constr.newInstance();
		var str   =inst.toString();
		
		var expected=new ISayHello().toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(str, expected);
	}
	
	@Test
	void dummyClass() throws ReflectiveOperationException{
		var msg="Ayyyy it works!";
		
		var className="jorth.Gen$$";
		var cls=generateAndLoadInstance(className, writer->{
			
			//Define constants / imports
			writer.write("#TOKEN(0) Str define", String.class.getName());
			
			//define class
			writer.write(
				"""
					public visibility
					#TOKEN(0) class start
					""",
				className
			);
			
			writer.write(
				"""
					Str returns
					toString function start
					'#RAW(0)'
					function end
					""",
				msg);
		});
		
		var constr=cls.getConstructor();
		var inst  =constr.newInstance();
		var str   =inst.toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(msg, str);
	}
	
	Class<?> generateAndLoadInstance(String className, UnsafeConsumer<JorthWriter, MalformedJorthException> generator) throws ReflectiveOperationException{
		
		var cls=Class.forName(className, false, new ClassLoader(){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(name.equals(className)){
					var jorth=new JorthCompiler();
					
					try(var writer=jorth.writeCode()){
						generator.accept(writer);
					}catch(MalformedJorthException e){
						throw new RuntimeException("Failed to generate class "+className, e);
					}
					
					var byt=jorth.classBytecode();
					BytecodeUtils.printClass(byt);
					
					return defineClass(name, ByteBuffer.wrap(byt), null);
				}
				return super.findClass(name);
			}
		});
		assert cls.getName().equals(className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		
		return cls;
	}
	
}
