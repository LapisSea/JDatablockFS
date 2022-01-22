package com.lapissea.jorth;

import com.lapissea.jorth.lang.BytecodeUtils;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JorthTests{
	
	public static void main(String[] args) throws Throwable{
		new JorthTests().comparisonTest();
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
			writer.write("#TOKEN(0) Obj define", Object.class.getName());
			
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
					[$type1 $type2 $returnType $opName $op] mathOp macro start
						static
						$type1 arg1 arg
						$type2 arg2 arg
						$returnType returns
						$opName function start
							
							<arg> arg1 get
							<arg> arg2 get
							$op
							$returnType cast
						end
					macro end
					
					[$typ1 $typ2 $returnTyp] mathOps macro start
						[$typ1 $typ2 $returnTyp add +] mathOp macro resolve
						[$typ1 $typ2 $returnTyp sub -] mathOp macro resolve
						[$typ1 $typ2 $returnTyp div /] mathOp macro resolve
						[$typ1 $typ2 $returnTyp mul *] mathOp macro resolve
					macro end
					
					
					[byte   byte   byte  ] mathOps macro resolve
					[short  short  short ] mathOps macro resolve
					[int    int    int   ] mathOps macro resolve
					[long   long   long  ] mathOps macro resolve
					[float  float  float ] mathOps macro resolve
					[double double double] mathOps macro resolve
					""");
		});
		
		Object ival;
		
		
		ival=cls.getMethod("add", int.class, int.class).invoke(null, 10, 2);
		assertEquals(12, ival);
		
		ival=cls.getMethod("sub", int.class, int.class).invoke(null, 10, 2);
		assertEquals(8, ival);
		
		ival=cls.getMethod("mul", int.class, int.class).invoke(null, 10, 2);
		assertEquals(20, ival);
		
		ival=cls.getMethod("div", int.class, int.class).invoke(null, 10, 2);
		assertEquals(5, ival);
		
		
		ival=cls.getMethod("add", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10+0.2F, ival);
		
		ival=cls.getMethod("sub", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10-0.2F, ival);
		
		ival=cls.getMethod("mul", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10*0.2F, ival);
		
		ival=cls.getMethod("div", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10/0.2F, ival);
		
		
		ival=cls.getMethod("add", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10+0.2D, ival);
		
		ival=cls.getMethod("sub", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10-0.2D, ival);
		
		ival=cls.getMethod("mul", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10*0.2D, ival);
		
		ival=cls.getMethod("div", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10/0.2D, ival);
		
	}
	@Test
	void concatTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
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
			
			writer.write(
				"""
					[$type] myMacro macro start
						static
						$type myArgumentName arg
						Str returns
						myFunctionName function start
							
							<arg> myArgumentName get
							'string concat works with $type:\n'
							concat
							
						end
					macro end
					""");
			writer.write(
				"""
					{Str $type}    myMacro macro resolve
					{Obj $type}    myMacro macro resolve
					{byte $type}   myMacro macro resolve
					{short $type}  myMacro macro resolve
					{int $type}    myMacro macro resolve
					{long $type}   myMacro macro resolve
					{float $type}  myMacro macro resolve
					{double $type} myMacro macro resolve
					""");
		});


//		LogUtil.println(cls.getMethod("myFunctionName", String.class).invoke(null, (Object)null));
		LogUtil.println(cls.getMethod("myFunctionName", String.class).invoke(null, "this is a test"));
		LogUtil.println(cls.getMethod("myFunctionName", int.class).invoke(null, 123));
		LogUtil.println(cls.getMethod("myFunctionName", float.class).invoke(null, 0.123F));
		LogUtil.println(cls.getMethod("myFunctionName", double.class).invoke(null, 0.345));
		
	}
	
	@Test
	void comparisonTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
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
			
			writer.write(
				"""
					[$typ] compareFunct macro start
						static
						$typ arg1 arg
						$typ arg2 arg
						boolean returns
						compare function start
							<arg> arg1 get
							<arg> arg2 get
							==
						end
					macro end
					""");
			
			writer.write("[Str] compareFunct macro resolve");
			writer.write("[int] compareFunct macro resolve");
		});
		
		var testStr=cls.getMethod("compare", String.class, String.class);
		assertEquals(false, testStr.invoke(null, "0", "1"));
		assertEquals(true, testStr.invoke(null, "1", "1"));
		assertEquals(false, testStr.invoke(null, null, "1"));
		assertEquals(false, testStr.invoke(null, "0", null));
		assertEquals(true, testStr.invoke(null, null, null));
		
		var test=cls.getMethod("compare", int.class, int.class);
		assertEquals(false, test.invoke(null, 0, 1));
		assertEquals(true, test.invoke(null, 1, 1));
	}
	
	@Test
	void ifTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstance(className, writer->{
			
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
			
			writer.write(
				"""
					static
					int index arg
					Str returns
					test function start
						
						<arg> index get 1 == if
							'ay'
						else
							lmao
						end
						
					end
					""");
		});
		
		var test=cls.getMethod("test", int.class);
		
		LogUtil.println(test.invoke(null, 0));
		LogUtil.println(test.invoke(null, 1));
		LogUtil.println(test.invoke(null, 2));
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
					end
					""");
			
			writer.write(
				"""
					Str testString arg
					init function start
					
					<arg> testString get
					this testString set
					
					end
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
					end
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
		
		var cls=Class.forName(className, true, new ClassLoader(this.getClass().getClassLoader()){
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
		});
		assert cls.getName().equals(className);
		
		LogUtil.println("Compiled:", cls);
		LogUtil.println("========================================================================");
		LogUtil.println();
		
		return cls;
	}
	
}
