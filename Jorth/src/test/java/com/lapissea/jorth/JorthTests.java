package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
import org.testng.annotations.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static com.lapissea.jorth.TestUtils.generateAndLoadInstance;
import static com.lapissea.jorth.TestUtils.generateAndLoadInstanceSimple;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertTrue;

public class JorthTests{
	
	public static void main(String[] args) throws Throwable{
		new JorthTests().simpleEnum();
	}
	
	
	public static class TestCls{
		boolean flag = false;
		static boolean staticFlag = false;
		
		public void flag(){
			flag = true;
		}
		
		public static void staticFlag(){
			staticFlag = true;
		}
	}
	
	public static class ISayHello{
		@Override
		public String toString(){
			return "Hello from ISayHello";
		}
	}
	
	//TODO: Implement math
	@Test(expectedExceptions = NotImplementedException.class)
	void mathClass() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstance(className, writer -> {
			//define class
			writer.write("public class {!} start", className);
			
			var types = List.of("byte", "short", "int", "long", "float", "double");
			for(int i = 0; i<types.size(); i++){
				var type1 = types.get(i);
				for(int j = 0; j<types.size(); j++){
					var type2 = types.get(j);
					
					String returnType;
					var    k = Math.max(i, j);
					if((k == 4 || k == 5) && (i == 3 || j == 3)){
						returnType = "double";
					}else{
						returnType = types.get(k);
					}
					
					for(var e : Map.of(
						"add", "+",
						"sub", "-",
						"div", "/",
						"mul", "*"
					).entrySet()){
						writer.write(
							"""
								static function {!}
									arg arg1 {}
									arg arg2 {}
									returns {}
								start
									get #arg arg1
									get #arg arg2
									{!}
								end
								""",
							e.getKey(),
							type1, type2, returnType,
							e.getValue());
					}
				}
			}
		});
		
		Object ival;
		
		
		ival = cls.getMethod("add", int.class, int.class).invoke(null, 10, 2);
		assertEquals(12, ival);
		
		ival = cls.getMethod("sub", int.class, int.class).invoke(null, 10, 2);
		assertEquals(8, ival);
		
		ival = cls.getMethod("mul", int.class, int.class).invoke(null, 10, 2);
		assertEquals(20, ival);
		
		ival = cls.getMethod("div", int.class, int.class).invoke(null, 10, 2);
		assertEquals(5, ival);
		
		
		ival = cls.getMethod("add", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10 + 0.2F, ival);
		
		ival = cls.getMethod("sub", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10 - 0.2F, ival);
		
		ival = cls.getMethod("mul", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10*0.2F, ival);
		
		ival = cls.getMethod("div", float.class, float.class).invoke(null, 10, 0.2F);
		assertEquals(10/0.2F, ival);
		
		
		ival = cls.getMethod("add", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10 + 0.2D, ival);
		
		ival = cls.getMethod("sub", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10 - 0.2D, ival);
		
		ival = cls.getMethod("mul", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10*0.2D, ival);
		
		ival = cls.getMethod("div", double.class, double.class).invoke(null, 10, 0.2D);
		assertEquals(10/0.2D, ival);
	}
	
	@Test
	void comparisonTest() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			
			for(var typ : List.of("#String", "int")){
				writer.write(
					"""
						static function compare
							arg arg1 {0}
							arg arg2 {0}
							returns boolean
						start
							get #arg arg1
							get #arg arg2
							==
						end
						""",
					typ);
			}
		});
		
		var testStr = cls.getMethod("compare", String.class, String.class);
		assertEquals(false, testStr.invoke(null, "0", "1"));
		assertEquals(true, testStr.invoke(null, "1", "1"));
		assertEquals(false, testStr.invoke(null, null, "1"));
		assertEquals(false, testStr.invoke(null, "0", null));
		assertEquals(true, testStr.invoke(null, null, null));
		
		var test = cls.getMethod("compare", int.class, int.class);
		assertEquals(false, test.invoke(null, 11, 10));
		assertEquals(true, test.invoke(null, 10, 10));
	}
	
	@Test
	void ifTest() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.write(
				"""
					static function test
						arg index int
						returns #String
					start
						get #arg index 1 ==
						if start
							'ay'
							return
						end
						new #StringBuilder
						call append start 'lmao ' end
						call append start get #arg index end
						call toString
					end
					""");
		});
		
		var test = cls.getMethod("test", int.class);
		
		assertEquals("lmao 0", test.invoke(null, 0));
		assertEquals("ay", test.invoke(null, 1));
		assertEquals("lmao 2", test.invoke(null, 2));
	}
	
	@Test
	void functionCallTest() throws ReflectiveOperationException{
		
		var cls = generateAndLoadInstanceSimple(TestCls.class.getPackageName() + ".Gen$$", writer -> {
			writer.addImport(LogUtil.class);
			writer.addImportAs(TestCls.class, "TestCls");
			writer.write(
				"""
					
					static function printToConsole
					start
						static call #LogUtil println start
							'AAAYYYY LMAO'
						end
					end
					
					static function testFlag
						arg obj #TestCls
					start
						get #arg obj
						call flag
						
						static call {} staticFlag
					end
					
					function concatCal
						arg a #String
						arg b #String
						returns #String
					start
						new #StringBuilder start get #arg a end
						call append start get #arg b end
						call toString
					end
					
					function useCall
						returns #String
					start
						get this this
						'ay '
						call concat start
							'lmao'
						end
					end
					""",
				TestCls.class.getName());
		});
		TestCls test = new TestCls();
		assertFalse(test.flag);
		cls.getMethod("testFlag", TestCls.class).invoke(null, test);
		assertTrue(test.flag);
		
		var inst = cls.getConstructor().newInstance();
		assertEquals("ay lmao", cls.getMethod("useCall").invoke(inst));
		
		cls.getMethod("printToConsole").invoke(null);
	}
	
	@Test
	void fieldClass() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			
			writer.write(
				"""
					public field testString #String
					"""
			);
			
			writer.write(
				"""
					function toString
						returns #String
					start
						get this testString
					end
					""");
			
			writer.write(
				"""
					function init
						arg testString #String
					start
						get #arg testString
						set this testString
					end
					""");
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		var msg = "this is a test";
		
		cls.getMethod("init", String.class).invoke(inst, msg);
		
		var str = inst.toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(msg, str);
	}
	
	@Test
	void fieldArrayClass() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			
			writer.write(
				"""
					public field noArray #String
					public field 1dArray #String array
					public field 2dArray #String array array
					"""
			);
		});
		
		for(Field field : cls.getFields()){
			LogUtil.println(field.toString(), field.getGenericType().toString());
		}
		
		assertFalse(cls.getField("noArray").getType().isArray());
		assertTrue(cls.getField("1dArray").getType().isArray());
		assertTrue(cls.getField("2dArray").getType().componentType().isArray());
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
		
		public String     a;
		public String[]   b;
		public String[][] c;
	}
	
	@Test
	void fieldAnnotation() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.addImportAs(MultiAnn.class, "Ann");
			writer.write(
				"""
					@ #Ann start value 141 lol 'xD' end
					public field testString #String
					"""
			);
		});
		var anns = cls.getFields()[0].getDeclaredAnnotations();
		LogUtil.println((Object[])anns);
		assertEquals(1, anns.length);
		var ann = (MultiAnn)anns[0];
		assertEquals("xD", ann.lol());
		assertEquals(141, ann.value());
	}
	
	@Test
	void methodAnnotation() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.addImportAs(MultiAnn.class, "Ann");
			writer.write(
				"""
					@ #Ann start value 141 lol 'xD' end
					public function test start end
					"""
			);
		});
		var anns = cls.getMethod("test").getAnnotations();
		LogUtil.println((Object[])anns);
		assertEquals(1, anns.length);
		var ann = (MultiAnn)anns[0];
		assertEquals("xD", ann.lol());
		assertEquals(141, ann.value());
	}
	@Test
	void classAnnotation() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstance(className, writer -> {
			writer.addImportAs(MultiAnn.class, "Ann");
			writer.write(
				"""
					@ #Ann start value 141 lol 'xD' end
					public class {!} start end
					""",
				className
			);
		});
		var anns = cls.getAnnotations();
		LogUtil.println((Object[])anns);
		assertEquals(1, anns.length);
		var ann = (MultiAnn)anns[0];
		assertEquals("xD", ann.lol());
		assertEquals(141, ann.value());
	}
	
	@Test
	void overrideClass() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					extends {!1}<{!0}>
					public class {!0} start
					end
					""",
				className,
				ISayHello.class.getName()
			);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		var str    = inst.toString();
		
		var expected = new ISayHello().toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(str, expected);
	}
	
	@Test
	void dummyClass() throws ReflectiveOperationException{
		var msg = "Ayyyy it works!";
		
		var className = "jorth.Gen$$";
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.write(
				"""
					function toString
						returns #String
					start
						'{}'
					end
					""",
				msg);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		var str    = inst.toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(msg, str);
	}
	
	@SuppressWarnings("unchecked")
	@Test
	<T extends Enum<T>> void simpleEnum() throws ReflectiveOperationException{
		
		var className = "com.lapissea.jorth.WtfIsMyEnumAAA";
		var cls = generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					public enum {!} start
						enum FOO
						enum BAR
					end
					""",
				className);
		});
		cls.getEnumConstants();
		LogUtil.println(cls.getEnumConstants());
		
		assertEquals(List.of("FOO", "BAR"), EnumSet.allOf((Class<T>)cls).stream().map(Enum::name).toList());
	}
	
	@Test
	<T extends Enum<T>> void simpleInterface() throws ReflectiveOperationException{
		
		var className = "test.Interf";
		var cls = generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					public interface {!} start
						function hello
							returns #String
						end
					end
					""",
				className);
		});
		assertTrue(cls.isInterface());
		var hello = cls.getMethod("hello");
		LogUtil.println(hello);
		assertTrue(Modifier.isAbstract(hello.getModifiers()));
	}
	
	@Test
	void getClassRef() throws ReflectiveOperationException{
		var cls = generateAndLoadInstanceSimple("jorth.Gen$$", writer -> {
			writer.write(
				"""
					static function getCls
						returns #Class<#String>
					start
						class #String
					end
					"""
			);
		});
		
		var expected = String.class;
		var actual   = cls.getMethod("getCls").invoke(null);
		
		assertEquals(actual, expected);
	}
	@Test
	void superArgs() throws ReflectiveOperationException{
		
		var className   = "jorth.Gen$$";
		var expectedStr = "Hi from super";
		var cls = generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					extends {!1}
					public class {!0} start
						
						function <init> start
							super start
								'{2}'
							end
						end
						
					end
					""",
				className,
				IStoreHello.class.getName(),
				expectedStr
			);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		var str    = inst.toString();
		
		LogUtil.println(cls, "says", str);
		assertEquals(expectedStr, str);
	}
}
