package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Function;

import static com.lapissea.jorth.TestUtils.generateAndLoadInstance;
import static com.lapissea.jorth.TestUtils.generateAndLoadInstanceSimple;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JorthTests{
	
	public static void main(String[] args) throws Throwable{
		new JorthTests().fieldAnnotationClass();
	}
	
	
	public static class TestCls{
		boolean flag=false;
		static boolean staticFlag=false;
		
		public void flag(){
			flag=true;
		}
		
		public static void staticFlag(){
			staticFlag=true;
		}
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
		var str      ="string concat works with $type: ";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
			writer.write(
				"""
					[$type] myMacro macro start
						static
						$type myArgumentName arg
						Str returns
						myFunctionName function start
							
							'#RAW(0)'
							<arg> myArgumentName get
							concat
							
						end
					macro end
					""",
				str);
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
		
		
		LogUtil.println(cls.getMethod("myFunctionName", Object.class).invoke(null, new ISayHello()));
		LogUtil.println(cls.getMethod("myFunctionName", String.class).invoke(null, (Object)null));
		LogUtil.println(cls.getMethod("myFunctionName", String.class).invoke(null, "this is a test"));
		LogUtil.println(cls.getMethod("myFunctionName", int.class).invoke(null, 123));
		LogUtil.println(cls.getMethod("myFunctionName", long.class).invoke(null, 123L));
		LogUtil.println(cls.getMethod("myFunctionName", float.class).invoke(null, 0.123F));
		LogUtil.println(cls.getMethod("myFunctionName", double.class).invoke(null, 0.345));
		
	}
	
	@Test
	void comparisonTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
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
		assertEquals(false, test.invoke(null, 11, 10));
		assertEquals(true, test.invoke(null, 10, 10));
	}
	
	@Test
	void ifTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
			writer.write(
				"""
					static
					int index arg
					Str returns
					test function start
						
						<arg> index get 1 == if
							'ay'
						else
							'lmao ' <arg> index get concat
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
	void whileTest() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
			writer.write(
				"""
					static
					int start arg
					Str returns
					testWhile function start
						<arg> start get
						while dup 0 > start
							5 -
						end
					end
					""");
		});
		
		Function<Integer, Integer> testWhileControl=start->{
			int val=start;
			while(val>0){
				val-=5;
			}
			return val;
		};
		
		var testWhile=cls.getMethod("testWhile", int.class);
		
		int[] a=new int[20];
		int[] b=new int[20];
		for(int i=0;i<20;i++){
			a[i]=testWhileControl.apply(i);
			b[i]=(int)testWhile.invoke(null, i);
		}
		
		assertArrayEquals(a, b);
	}
	
	@Test
	void functionCallTest() throws ReflectiveOperationException{
		
		var cls=generateAndLoadInstanceSimple(TestCls.class.getPackageName()+".Gen$$", writer->{
			writer.write("#TOKEN(0) LogUtil define", LogUtil.class.getName());
			writer.write(
				"""
					
					static
					printToConsole function start
						'AAAYYYY LMAO'
						LogUtil println (1) static call
					end
					
					static
					#TOKEN(0) obj arg
					testFlag function start
						<arg> obj get
						flag (0) call
						
						#TOKEN(0) staticFlag (0) static call
					end
					
					Str a arg
					Str b arg
					Str returns
					concatCall function start
						<arg> a get
						<arg> b get
						concat
					end
					
					Str returns
					useCall function start
						
						this this get
						'ay ' 'lmao'
						concatCall (2) call
					end
					""",
				TestCls.class.getName());
		});
		
		TestCls test=new TestCls();
		assertFalse(test.flag);
		cls.getMethod("testFlag", TestCls.class).invoke(null, test);
		assertTrue(test.flag);
		
		var inst=cls.getConstructor().newInstance();
		LogUtil.println(cls.getMethod("useCall").invoke(inst));
		
		cls.getMethod("printToConsole").invoke(null);
	}
	
	@Test
	void fieldClass() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
			
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
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface ValueAnn{
		int value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@interface DefaultAnn{
		int value() default 123;
	}
	@Retention(RetentionPolicy.RUNTIME)
	@interface MultiAnn{
		int value();
		String lol();
	}
	
	static class ay{
		
		@DefaultAnn(1234)
		public String field;
	}
	
	@Test
	void fieldAnnotationClass() throws ReflectiveOperationException{
		
		var className="jorth.Gen$$";
		
		var cls=generateAndLoadInstanceSimple(className, writer->{
			writer.write("#TOKEN(0) Ann define", MultiAnn.class.getName());
			writer.write(
				"""
					public visibility
					{141 value 'xD' lol} Ann @
					Str testString field
					"""
			);
		});
		
		LogUtil.println(cls.getFields()[0].getDeclaredAnnotations());
		
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
		var cls=generateAndLoadInstanceSimple(className, writer->{
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
	
}
