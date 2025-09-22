package com.lapissea.jorth;

import com.lapissea.util.LogUtil;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.lapissea.jorth.TestUtils.generateAndLoadInstance;
import static com.lapissea.jorth.TestUtils.generateAndLoadInstanceSimple;
import static org.assertj.core.api.Assertions.assertThat;

public class JorthTests{
	public interface UnsafeBiPredicate<T, U, E extends Exception>{
		boolean test(T t, U u) throws E;
	}
	
	static{
		Thread.startVirtualThread(() -> new Jorth(null, null));
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
	
	public abstract static class IStoreHello{
		
		private final String str;
		protected IStoreHello(String str){
			this.str = str;
		}
		
		@Override
		public String toString(){
			return str;
		}
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
		UnsafeBiPredicate<String, String, ReflectiveOperationException> testStrFn = (a, b) -> {
			var res = testStr.invoke(null, a, b);
			assertThat(res).as("Compare should return boolean").isInstanceOf(Boolean.class);
			return (boolean)res;
		};
		
		assertThat(testStrFn.test("0", "1")).isFalse();
		assertThat(testStrFn.test("1", "1")).isTrue();
		assertThat(testStrFn.test(null, "1")).isFalse();
		assertThat(testStrFn.test("0", null)).isFalse();
		assertThat(testStrFn.test(null, null)).isTrue();
		
		var test = cls.getMethod("compare", int.class, int.class);
		UnsafeBiPredicate<Integer, Integer, ReflectiveOperationException> testFn = (a, b) -> {
			var res = test.invoke(null, a, b);
			assertThat(res).as("Compare should return boolean").isInstanceOf(Boolean.class);
			return (boolean)res;
		};
		assertThat(testFn.test(11, 10)).isFalse();
		assertThat(testFn.test(10, 10)).isTrue();
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
		
		assertThat(test.invoke(null, 0)).isEqualTo("lmao 0");
		assertThat(test.invoke(null, 1)).isEqualTo("ay");
		assertThat(test.invoke(null, 2)).isEqualTo("lmao 2");
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
		assertThat(test.flag).isFalse();
		cls.getMethod("testFlag", TestCls.class).invoke(null, test);
		assertThat(test.flag).isTrue();
		
		var inst = cls.getConstructor().newInstance();
		assertThat(cls.getMethod("useCall").invoke(inst)).isEqualTo("ay lmao");
		
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
		
		assertThat(inst).asString().isEqualTo(msg);
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
		
		assertThat(cls.getField("noArray").getType().isArray()).isFalse();
		assertThat(cls.getField("1dArray").getType().isArray()).isTrue();
		assertThat(cls.getField("2dArray").getType().componentType().isArray()).isTrue();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface ValueAnn{
		int value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface DefaultAnn{
		int value() default 123;
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface MultiAnn{
		int value();
		String lol();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	public @interface EnumAnn{
		RetentionPolicy value();
	}
	
	static class ay{
		
		@DefaultAnn(1234)
		public String field;
		
		public String     a;
		public String[]   b;
		public String[][] c;
	}
	
	@Test
	void defaultAnnotation() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.addImportAs(DefaultAnn.class, "Ann");
			writer.write(
				"""
					@ #Ann start value 321 end
					public field a #String
					
					@ #Ann
					public field b #String
					"""
			);
		});
		var a = (DefaultAnn)cls.getField("a").getDeclaredAnnotations()[0];
		var b = (DefaultAnn)cls.getField("b").getDeclaredAnnotations()[0];
		assertThat(a.value()).isEqualTo(321);
		assertThat(b.value()).isEqualTo(123);
	}
	
	@Test
	void enumAnnotation() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstanceSimple(className, writer -> {
			writer.addImportAs(EnumAnn.class, "Ann");
			writer.write(
				"""
					@ #Ann start value CLASS end
					public field a #String
					"""
			);
		});
		var a = (EnumAnn)cls.getField("a").getDeclaredAnnotations()[0];
		assertThat(a.value()).isEqualTo(RetentionPolicy.CLASS);
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
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
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
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
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
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
	}
	
	@Test
	void overrideClass() throws ReflectiveOperationException{
		
		var className = "jorth.Gen$$";
		
		var cls = generateAndLoadInstance(className, writer -> {
			writer.write(
				"""
					extends {!1}
					public class {!0} start
					end
					""",
				className,
				ISayHello.class.getName()
			);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		var expected = new ISayHello().toString();
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(expected);
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
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(msg);
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
		
		assertThat(EnumSet.allOf((Class<T>)cls).stream().map(Enum::name)).containsExactly("FOO", "BAR");
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
		assertThat(cls).isInterface();
		var hello = cls.getMethod("hello");
		LogUtil.println(hello);
		assertThat(Modifier.isAbstract(hello.getModifiers())).as("Method should be abstract").isTrue();
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
		
		assertThat(actual).isEqualTo(expected);
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
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(expectedStr);
	}
	
	@Test
	void genericFieldDefine() throws Exception{
		var cls = generateAndLoadInstanceSimple("jorth.Gen$$", writer -> {
			writer.addImport(Optional.class);
			writer.write(
				"""
					field optStr #Optional<#String>
					"""
			);
		});
		
		var generic = (ParameterizedType)cls.getField("optStr").getGenericType();
		
		assertThat(generic.getRawType()).isEqualTo(Optional.class);
		assertThat(generic.getActualTypeArguments()).containsExactly(String.class);
	}
	
	@Test(dependsOnMethods = "genericFieldDefine")
	void genericField() throws Exception{
		var cls = generateAndLoadInstanceSimple("jorth.Gen$$", writer -> {
			writer.addImport(Optional.class);
			writer.write(
				"""
					field optStr #Optional<#String>
					
					function set
						arg optStr #Optional<#String>
					start
						get #arg optStr
						set this optStr
					end
					
					"""
			);
		});
		
		var generic = (ParameterizedType)cls.getField("optStr").getGenericType();
		
		assertThat(generic.getRawType()).isEqualTo(Optional.class);
		assertThat(generic.getActualTypeArguments()).containsExactly(String.class);
	}
	
	static class Typ{ }
	
	void upper(List<? extends Typ> arg){ }
	void lower(List<? super Typ> arg)  { }
	void wild(List<?> arg)             { }
	
	@Test
	void wildcardUpper() throws Exception{
		
		{
			var fun = JorthTests.class.getDeclaredMethod("upper", List.class);
			var typ = (WildcardType)((ParameterizedType)fun.getGenericParameterTypes()[0]).getActualTypeArguments()[0];
			assertThat(typ.getLowerBounds()).isEmpty();
			assertThat(typ.getUpperBounds()).containsExactly(Typ.class);
		}
		{
			var fun = JorthTests.class.getDeclaredMethod("lower", List.class);
			var typ = (WildcardType)((ParameterizedType)fun.getGenericParameterTypes()[0]).getActualTypeArguments()[0];
			assertThat(typ.getLowerBounds()).containsExactly(Typ.class);
			assertThat(typ.getUpperBounds()).containsExactly(Object.class);
		}
		{
			var fun = JorthTests.class.getDeclaredMethod("wild", List.class);
			var typ = (WildcardType)((ParameterizedType)fun.getGenericParameterTypes()[0]).getActualTypeArguments()[0];
			assertThat(typ.getLowerBounds()).isEmpty();
			assertThat(typ.getUpperBounds()).containsExactly(Object.class);
		}
		
		var cls = generateAndLoadInstanceSimple("jorth.Gen$$", writer -> {
			writer.addImport(List.class);
			writer.addImport(Typ.class);
			writer.write(
				"""
					
					function upper
						arg arg #List<? extends #JorthTests.Typ>
					start end
					
					function lower
						arg arg #List<? super #JorthTests.Typ>
					start end
					
					function wild
						arg arg #List<?>
					start end
					"""
			);
		});
		
		{
			var funCtrl = JorthTests.class.getDeclaredMethod("upper", List.class);
			var fun     = cls.getDeclaredMethod("upper", List.class);
			assertThat(fun).extracting("genericParameterTypes").isEqualTo(funCtrl.getGenericParameterTypes());
		}
		{
			var funCtrl = JorthTests.class.getDeclaredMethod("lower", List.class);
			var fun     = cls.getDeclaredMethod("lower", List.class);
			assertThat(fun).extracting("genericParameterTypes").isEqualTo(funCtrl.getGenericParameterTypes());
		}
		{
			var funCtrl = JorthTests.class.getDeclaredMethod("wild", List.class);
			var fun     = cls.getDeclaredMethod("wild", List.class);
			assertThat(fun).extracting("genericParameterTypes").isEqualTo(funCtrl.getGenericParameterTypes());
		}
	}
	
	
	@Test
	void sealedClass() throws Exception{
		var cls = generateAndLoadInstance("SealedClass", writer -> {
			writer.write(
				"""
					permits child1
					permits child2
					public class SealedClass start end
					
					extends SealedClass
					final class child1 start end
					extends SealedClass
					final class child2 start end
					"""
			);
		});
		
		var permits = cls.getPermittedSubclasses();
		assertThat(permits).isNotNull();
		assertThat(Arrays.stream(permits).map(Class::getName)).containsExactlyInAnyOrder("child1", "child2");
	}
	
	@Test
	void parmClass() throws Exception{
		var cls = generateAndLoadInstance("ParmClass", writer -> {
			writer.addImport(CharSequence.class);
			writer.addImport(List.class);
			writer.write(
				"""
					type-arg T #CharSequence
					public class ParmClass start
					
					end
					"""
			);
		});
		
		var parms = cls.getTypeParameters();
		assertThat(parms).hasSize(1);
		var parm = parms[0];
		assertThat(parm.getName()).isEqualTo("T");
		assertThat(parm.getBounds()).containsExactly(CharSequence.class);
	}
	
	@Test(dependsOnMethods = "parmClass")
	void parmClassFun() throws Exception{
		var cls = generateAndLoadInstance("ParmClass", writer -> {
			writer.addImport(CharSequence.class);
			writer.addImport(List.class);
			writer.write(
				"""
					type-arg T #CharSequence
					public class ParmClass start
					
						function takeArg
							arg tList #List<T>
						start
						end
					
					end
					"""
			);
		});
		
		var meth  = cls.getMethod("takeArg", List.class);
		var parms = meth.getGenericParameterTypes();
		assertThat(parms).hasSize(1);
		assertThat(parms[0]).isInstanceOf(ParameterizedType.class);
		var arg1 = ((ParameterizedType)parms[0]).getActualTypeArguments()[0];
		assertThat(arg1).isInstanceOf(TypeVariable.class);
		var targ = (TypeVariable<?>)arg1;
		assertThat(targ.getName()).isEqualTo("T");
		assertThat(targ.getBounds()).containsExactly(CharSequence.class);
	}
	
	@Test(dependsOnMethods = "parmClass")
	void parmClassField() throws Exception{
		var cls = generateAndLoadInstance("ParmClass", writer -> {
			writer.addImport(CharSequence.class);
			writer.write(
				"""
					type-arg T #CharSequence
					public class ParmClass start
					
						field arg T
					
					end
					"""
			);
		});
		
		var field = cls.getField("arg");
		var type  = field.getGenericType();
		assertThat(type).isInstanceOf(TypeVariable.class);
		var ttyp = (TypeVariable<?>)type;
		assertThat(ttyp.getName()).isEqualTo("T");
		assertThat(ttyp.getBounds()).containsExactly(CharSequence.class);
	}
	
	private record Prop(String name, Class<?> type, Object defaultVal){ }
	
	@DataProvider
	Object[][] props(){
		return new Object[][]{
			{List.of(new Prop("foo", int.class, 69))},
			{List.of()},
			{List.of(new Prop("foo", int.class, 69), new Prop("bar", float.class, 69.0F))},
			};
	}
	
	@Test(dataProvider = "props")
	void templateFor(List<Prop> props) throws Exception{
		
		var name = "Props" + props.stream().map(Prop::name).collect(Collectors.joining());
		var cls = generateAndLoadInstance(name, writer -> {
			writer.addImport(CharSequence.class);
			writer.write(
				"""
					class {0} start
					
						template-for #field in {1} start
							public field #field.name #field.type
						end
					
						function <init> start
							super
					
							template-for #field in {1} start
								#field.defaultVal set this #field.name
							end
						end
					end
					""",
				name, props
			);
		});
		
		assertThat(Arrays.stream(cls.getFields()).map(Field::getName))
			.containsExactlyInAnyOrderElementsOf(props.stream().map(Prop::name).toList());
		
		var inst = cls.getConstructor().newInstance();
		for(Field field : cls.getFields()){
			var expected = props.stream().filter(f -> f.name.equals(field.getName())).findAny().orElseThrow().defaultVal;
			assertThat(field.get(inst)).as("Invalid default value for " + field.getName()).isEqualTo(expected);
		}
	}
	
	@Test
	void templateForRaw() throws Exception{
		var names = Set.of("a", "b", "c");
		var name  = "Props";
		var cls = generateAndLoadInstance(name, writer -> {
			writer.addImport(CharSequence.class);
			writer.write(
				"""
					class {0} start
					
						template-for #name in {1} start
							public field #name int
						end
					end
					""",
				name, names
			);
		});
		
		assertThat(Arrays.stream(cls.getFields()).map(Field::getName)).containsExactlyInAnyOrderElementsOf(names);
	}
	
	public static final class TestBootstrap{
		public static CallSite bootstrap(MethodHandles.Lookup lookup,
		                                 String name,
		                                 MethodType type,
		                                 Class<?> passType) throws Throwable{
			var cname = lookup.lookupClass().getName() + "&_" + name;
			
			var tokenStr = new StringJoiner(" ");
			var jorth    = new Jorth(null, tokenStr::add);
			
			try(var writer = jorth.writer()){
				writer.write(
					"""
						class {} start
						
						public static function {}
							arg num int
							returns #String
						start
							new #StringBuilder start
								'{} '
							end
							dup
							call append start
								get #arg num
							end
							call toString
							return
						end
						
						end
						""",
					cname,
					name,
					passType.getName()
				);
			}finally{
				LogUtil.println(tokenStr.toString());
			}
			
			var          bb        = jorth.getClassFile(cname);
			var          implClass = lookup.defineHiddenClass(bb, true);
			MethodHandle target    = implClass.unreflect(implClass.lookupClass().getMethod(name, int.class));
			return new ConstantCallSite(target);
		}
		
	}
	
	@Test
	void virtualCall() throws Exception{
		var name = "VCall";
		var cls = generateAndLoadInstance(name, writer -> {
			writer.addImport(IntFunction.class);
			writer.addImportAs(TestBootstrap.class, "TestBootstrap");
			
			// calling function is the final function that is returned from boostrap
			writer.write(
				"""
					implements #IntFunction<#String>
					class {0} start
						@ #Override
						public function apply
							arg num int
							returns #Object
						start
							call-virtual
								bootstrap-fn #TestBootstrap bootstrap
									arg #Class {0}
								calling-fn makeString start
									arg int
									returns #String
								end
							start
								get #arg num
							end
							return
						end
					end
					""",
				name
			);
		});
		
		Object instO = cls.getConstructor().newInstance();
		
		assertThat(instO).isInstanceOf(IntFunction.class);
		//noinspection unchecked
		IntFunction<String> inst = (IntFunction<String>)instO;
		assertThat(inst.apply(1)).isEqualTo(name + " 1");
		assertThat(inst.apply(69)).isEqualTo(name + " 69");
	}
}
