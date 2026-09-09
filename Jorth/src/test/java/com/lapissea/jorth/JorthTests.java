package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalConditionalMerge;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.exceptions.MissingLocalField;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.GenericType;
import com.lapissea.jorth.lang.type.JType;
import com.lapissea.util.LogUtil;
import com.lapissea.util.NotImplementedException;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

import static com.lapissea.jorth.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;

public class JorthTests{
	public interface UnsafeBiPredicate<T, U, E extends Exception>{
		boolean test(T t, U u) throws E;
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
	void explicitPrimitiveConversions() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var takeLong = cd.function("takeLong").staticAcc().arg(long.class, "x").returns(long.class);
			takeLong.body().get("x").returnOp();
			var takeInteger = cd.function("takeInteger").staticAcc().arg(Integer.class, "x").returns(Integer.class);
			takeInteger.body().get("x").returnOp();
			cd.function("returnLong").staticAcc().returns(long.class).body().val(5).cast(long.class).returnOp();
			cd.function("callLong").staticAcc().returns(long.class).body().val(5).cast(long.class).call(takeLong);
			cd.function("callInteger").staticAcc().returns(Integer.class).body().val(5).box().call(takeInteger);
		});
		assertThat(cls.getMethod("returnLong").invoke(null)).isEqualTo(5L);
		assertThat(cls.getMethod("callLong").invoke(null)).isEqualTo(5L);
		assertThat(cls.getMethod("callInteger").invoke(null)).isEqualTo(5);
	}

	@Test(expectedExceptions = MalformedJorth.class)
	void returnRequiresExplicitWidening() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd ->
			cd.function("run").staticAcc().returns(long.class).body().val(5).returnOp());
	}

	@Test(expectedExceptions = MalformedJorth.class)
	void callRequiresExplicitWidening() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd -> {
			var target = cd.function("target").staticAcc().arg(long.class, "x").returns(long.class);
			target.body().get("x");
			cd.function("run").staticAcc().returns(long.class).body().val(5).call(target);
		});
	}

	@Test(expectedExceptions = MalformedJorth.class)
	void callRequiresExplicitBoxing() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd -> {
			var target = cd.function("target").staticAcc().arg(Integer.class, "x").returns(Integer.class);
			target.body().get("x");
			cd.function("run").staticAcc().returns(Integer.class).body().val(5).call(target);
		});
	}

	public static class AutoPassSuper{
		public int doubleAndAdd(int x){
			return x*2 + 1;
		}
	}
	
	@Test
	void callSuperAutoPassForwardsArguments() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.extendsType(AutoPassSuper.class);
			cd.function("doubleAndAdd").arg(int.class, "x").returns(int.class)
			  .body().callSuperAutoPass().add(1);
		});
		var instance = cls.getConstructor().newInstance();
		assertThat(cls.getMethod("doubleAndAdd", int.class).invoke(instance, 41)).isEqualTo(41*2 + 1 + 1);
	}
	
	@Test(expectedExceptions = MalformedJorth.class,
	      expectedExceptionsMessageRegExp = "Cannot use 'this' from a static function")
	void callSuperAutoPassRejectsStaticContext() throws Exception{
		generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.extendsType(AutoPassSuper.class);
			cd.function("doubleAndAdd").staticAcc().arg(int.class, "x").returns(int.class)
			  .body().callSuperAutoPass();
		});
	}
	
	@Test
	void voidReturnsIgnoreRemainingStackValues() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("implicit").staticAcc().body()
			  .val(1).val(2L).val(3.0).val("incidental");
			cd.function("explicit").staticAcc().body()
			  .val(1).val(2L).val(3.0).val("incidental").returnOp();
		});
		assertThat(cls.getMethod("implicit").invoke(null)).isNull();
		assertThat(cls.getMethod("explicit").invoke(null)).isNull();
	}

	@Test
	void repeatedBodyCallsShareOneConcreteFunction() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var fn = cd.function("run").staticAcc().arg(int.class, "value").returns(int.class);
			assertThat(fn.access().isAbstract()).isTrue();
			
			var body = fn.body();
			assertThat(fn.access().isAbstract()).isFalse();
			assertThat(cd.getClassInfo().getFunction(fn.makeSignature())).isSameAs(fn);
			assertThat(fn.body()).isSameAs(body);
			
			body.get("value");
			fn.body().add(2);
			fn.body().returnOp();
			assertThat(fn.body()).isSameAs(body);
		});
		assertThat(cls.getDeclaredMethods()).hasSize(1);
		var method = cls.getMethod("run", int.class);
		assertThat(Modifier.isAbstract(method.getModifiers())).isFalse();
		assertThat(method.invoke(null, 40)).isEqualTo(42);
	}
	
	@Test
	void comparisonTest() throws Exception{
		
		var cls = generateAndLoadInstanceSimple(autoName(), classDefinition -> {
			for(var typ : List.of(GenericType.STRING, GenericType.INT)){
				var fn = classDefinition.function("compare")
				                        .staticAcc()
				                        .arg(typ, "arg1").arg(typ, "arg2")
				                        .returns(boolean.class);
				fn.body()
				  .get("arg1")
				  .get("arg2")
				  .equalityOp();
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
	void ifTest() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var fn = cd.function("test").staticAcc()
			           .arg(GenericType.INT, "index")
			           .returns(GenericType.STRING);
			fn.body()
			  .get("index")
			  .val(1)
			  .ifEquality(code -> {
				  code.val("ay")
				      .returnOp();
			  })
			  .newObj(StringBuilder.class)
			  .call("append", c -> c.val("lmao "))
			  .call("append", c -> c.get("index"))
			  .call("toString", 0);
		});
		
		var test = cls.getMethod("test", int.class);
		
		assertThat(test.invoke(null, 0)).isEqualTo("lmao 0");
		assertThat(test.invoke(null, 1)).isEqualTo("ay");
		assertThat(test.invoke(null, 2)).isEqualTo("lmao 2");
	}
	
	private static final List<String> ifElseCalls = new ArrayList<>();
	private static void ifElseReport(String value){
		ifElseCalls.add(value);
	}
	
	@Test
	void ifElseTest() throws Exception{
		var className = autoName();
		var cls = generateAndLoadInstanceSimple(className, cd -> {
			var list = cd.field(GenericType.of(List.class).withArgs(String.class), "list")
			             .staticFinal(e -> e.newObj(ArrayList.class));
			
			var report = cd.function("report").staticAcc()
			               .arg(String.class, "str");
			report.body()
			      .get(list)
			      .call("add", c -> c.get("str"))
			      .pop();
			
			cd.function("test").staticAcc().arg(int.class, "index")
			  .body()
			  .call(report, c -> c.val("start"))
			  .get("index")
			  .val(0)
			  .equalityOp()
			  .ifTrue(code -> {
				  code.call(report, c -> c.val("ay"));
			  }).elseRun(code -> {
				  code.call(report, c -> c.val("lmao"));
			  })
			  .call(report, c -> c.val("end"));
			
		});
		//noinspection unchecked
		var list = (List<String>)cls.getField("list").get(null);
		
		var test = cls.getMethod("test", int.class);
		
		list.clear();
		test.invoke(null, 0);
		assertThat(list).containsExactly("start", "ay", "end");
		
		list.clear();
		test.invoke(null, 1);
		assertThat(list).containsExactly("start", "lmao", "end");
	}
	
	@DataProvider
	Object[][] equalityTypes(){
		return new Object[][]{
			{Object.class, new Object(), new Object(), false},
			{Object.class, "same", "same", true},
			{int.class, 1, 2, false},
			{int.class, 42, 42, true},
			{long.class, 1L, 2L, false},
			{long.class, 999L, 999L, true},
			{float.class, 1.0f, 2.0f, false},
			{float.class, 3.14f, 3.14f, true},
			{double.class, 1.0, 2.0, false},
			{double.class, 2.718, 2.718, true},
			{boolean.class, true, false, false},
			{boolean.class, true, true, true},
			{char.class, 'a', 'b', false},
			{char.class, 'x', 'x', true},
			{byte.class, (byte)1, (byte)2, false},
			{byte.class, (byte)7, (byte)7, true},
			{short.class, (short)1, (short)2, false},
			{short.class, (short)10, (short)10, true},
			};
	}
	
	@Test(dataProvider = "equalityTypes")
	void ifEqualsBranch(Class<?> type, Object arg1, Object arg2, boolean expectedEqual) throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstanceSimple(name, cd -> {
			var fn = cd.function("check").staticAcc()
			           .arg(type, "a").arg(type, "b")
			           .returns(int.class);
			fn.body()
			  .get("a")
			  .get("b")
			  .ifEquality(code -> {
				  code.val(1)
				      .returnOp();
			  })
			  .val(2);
		});
		
		var method    = cls.getMethod("check", type, type);
		int generated = (int)method.invoke(null, arg1, arg2);
		
		int expected = expectedEqual? 1 : 2;
		
		assertThat(generated).isEqualTo(expected);
	}
	
	@Test(dataProvider = "equalityTypes")
	void ifNotEqualsBranch(Class<?> type, Object arg1, Object arg2, boolean expectedEqual) throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstanceSimple(name, cd -> {
			var fn = cd.function("check").staticAcc()
			           .arg(type, "a").arg(type, "b")
			           .returns(int.class);
			fn.body()
			  .get("a")
			  .get("b")
			  .ifNotEquality(code -> {
				  code.val(1)
				      .returnOp();
			  })
			  .val(2);
		});
		
		var method    = cls.getMethod("check", type, type);
		int generated = (int)method.invoke(null, arg1, arg2);
		
		int expected = expectedEqual? 2 : 1;
		
		assertThat(generated).isEqualTo(expected);
	}
	
	@Test(dataProvider = "equalityTypes")
	void ifEqualsElseBranch(Class<?> type, Object arg1, Object arg2, boolean expectedEqual) throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstanceSimple(name, cd -> {
			var fn = cd.function("check").staticAcc()
			           .arg(type, "a").arg(type, "b")
			           .returns(int.class);
			fn.body()
			  .get("a")
			  .get("b")
			  .ifEquality(code -> {
				  code.val(1)
				      .returnOp();
			  })
			  .elseRun(code -> {
				  code.val(2)
				      .returnOp();
			  });
		});
		
		var method    = cls.getMethod("check", type, type);
		int generated = (int)method.invoke(null, arg1, arg2);
		
		int expected = expectedEqual? 1 : 2;
		
		assertThat(generated).isEqualTo(expected);
	}
	
	
	@DataProvider
	Object[][] ifMergeCases(){
		
		return new Object[][]{
			{true, true, true, true},
			{true, true, true, false},
			{true, false, true, true},
			{true, false, true, false},
			{true, false, false, true},
			{true, false, false, false},
			{false, true, true, true},
			{false, true, true, false},
			{false, false, true, true},
			{false, false, true, false},
			};
	}
	
	@Test(dataProvider = "ifMergeCases")
	void ifElseConditionalMergeTest(boolean returnsInTrue, boolean returnsInFalse, boolean hasFalse, boolean expectedEqual) throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstanceSimple(name, cd -> {
			var fn = cd.function("check").staticAcc()
			           .arg(int.class, "a")
			           .returns(int.class);
			var body = fn.body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
				    if(returnsInTrue) code.returnOp();
			    });
			if(hasFalse){
				body.elseRun(code -> {
					code.val(2);
					if(returnsInFalse) code.returnOp();
				});
			}else{
				body.val(2);
			}
			if(!returnsInTrue || !returnsInFalse){
				body.returnOp();
			}
		});
		
		var method    = cls.getMethod("check", int.class);
		int generated = (int)method.invoke(null, expectedEqual? 3 : 4);
		int expected  = expectedEqual? 1 : 2;
		
		
		assertThat(generated).isEqualTo(expected);
	}
	
	@Test(expectedExceptions = IllegalConditionalMerge.class)
	void ifBranchStackCountMismatchThrows() throws Exception{
		var name = autoName();
		generateAndLoadInstanceSimple(name, cd -> {
			var fn   = cd.function("check").staticAcc().arg(int.class, "a").returns(int.class);
			var body = fn.body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
			    })
			    .elseRun(code -> { });
			body.returnOp();
		});
	}
	@Test(expectedExceptions = IllegalConditionalMerge.class)
	void ifBranchStackCountMismatchThrows2() throws Exception{
		var name = autoName();
		generateAndLoadInstanceSimple(name, cd -> {
			var fn   = cd.function("check").staticAcc().arg(int.class, "a").returns(int.class);
			var body = fn.body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
			    });
			body.returnOp();
		});
	}
	
	@Test(expectedExceptions = IllegalConditionalMerge.class)
	void ifBranchStackTypeMismatchThrows() throws Exception{
		var name = autoName();
		generateAndLoadInstanceSimple(name, cd -> {
			var fn   = cd.function("check").staticAcc().arg(int.class, "a").returns(int.class);
			var body = fn.body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
			    })
			    .elseRun(code -> {
				    code.val(1L);
			    });
			body.returnOp();
		});
	}
	
	@Test(expectedExceptions = IllegalConditionalMerge.class)
	void ifNoElseImplicitMergeMismatchThrows() throws Exception{
		var name = autoName();
		generateAndLoadInstanceSimple(name, cd -> {
			var fn   = cd.function("check").staticAcc().arg(int.class, "a").returns(int.class);
			var body = fn.body();
			// no else: implicit false-branch is "entry stack unchanged" —
			// true-branch pushing a value with nothing consuming it after must fail
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1); // extra int left on stack, no else to match it against
			    });
			body.returnOp();
		});
	}
	
	private static void generateLazyBlock(CodeArg code) throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted("test.LazyBlockTermination"));
		cd.function("run").staticAcc().returns(int.class).body().lazyBlock(code).val(0);
		cd.getClassFile();
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "lazyBlock must not terminate")
	void lazyBlockRejectsReturn() throws Exception{
		generateLazyBlock(b -> b.val(1).returnOp());
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = "lazyBlock must not terminate")
	void lazyBlockRejectsTerminatingBranches() throws Exception{
		generateLazyBlock(b -> b.val(true).ifTrue(c -> c.val(1).returnOp()).elseRun(c -> c.val(2).returnOp()));
	}
	
	@Test
	void lazyBlockAllowsFallthrough() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd ->
			                                                    cd.function("run").staticAcc().arg(int.class, "value").returns(int.class).body()
			                                                      .lazyBlock(b -> b.val(42).set("value"))
			                                                      .get("value").returnOp()
		);
		assertThat(cls.getMethod("run", int.class).invoke(null, 0)).isEqualTo(42);
	}
	
	@Test(expectedExceptions = MalformedJorth.class, expectedExceptionsMessageRegExp = ".*block has terminated.*")
	void bothBranchesTerminateThenUnreachableAccessThrows() throws Exception{
		var name = autoName();
		generateAndLoadInstanceSimple(name, cd -> {
			var fn   = cd.function("check").staticAcc().arg(int.class, "a").returns(int.class);
			var body = fn.body();
			body.get("a").val(3)
			    .ifEquality(code -> {
				    code.val(1);
				    code.returnOp();
			    })
			    .elseRun(code -> {
				    code.val(2);
				    code.returnOp();
			    });
			// both branches terminate — this point is unreachable;
			// trying to keep building here (or requiring a final implicit return) should fail
			body.returnOp();
		});
	}
	
	@Test
	void functionCallTest() throws Exception{
		
		var cls = generateAndLoadInstanceSimple(TestCls.class.getPackageName() + ".Gen$$", cd -> {
			cd.function("printToConsole").staticAcc()
			  .body()
			  .call(LogUtil.class, "println", c -> c.val("AAAYYYY LMAO"));
			
			cd.function("testFlag").staticAcc().arg(TestCls.class, "obj")
			  .body()
			  .get("obj")
			  .call("flag")
			  .call(TestCls.class, "staticFlag", c -> { });
			
			cd.function("concatCal")
			  .arg(String.class, "a").arg(String.class, "b").returns(String.class)
			  .body()
			  .newObj(StringBuilder.class, c -> c.get("a"))
			  .call("append", c -> c.get("b"))
			  .call("toString");
			
			cd.function("useCall").returns(String.class)
			  .body()
			  .get("this")
			  .val("ay ")
			  .call("concat", c -> c.val("lmao"));
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
	void fieldClass() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var ts = cd.field(String.class, "testString");
			
			cd.function("toString").returns(String.class)
			  .body()
			  .getThis(ts);
			
			cd.function("init").arg(String.class, "testString")
			  .body()
			  .get("this")
			  .get("testString")
			  .setField(ts);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		var msg = "this is a test";
		
		cls.getMethod("init", String.class).invoke(inst, msg);
		
		assertThat(inst).asString().isEqualTo(msg);
	}
	
	@Test
	void fieldArrayClass() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(String.class, "noArray");
			cd.field(String[].class, "1dArray");
			cd.field(String[][].class, "2dArray");
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
	void defaultAnnotation() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(String.class, "a")
			  .annotation(DefaultAnn.class, a -> a.arg("value", 321));
			
			cd.field(String.class, "b")
			  .annotation(DefaultAnn.class);
		});
		var a = (DefaultAnn)cls.getField("a").getDeclaredAnnotations()[0];
		var b = (DefaultAnn)cls.getField("b").getDeclaredAnnotations()[0];
		assertThat(a.value()).isEqualTo(321);
		assertThat(b.value()).isEqualTo(123);
	}
	
	@Test
	void enumAnnotation() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(String.class, "a")
			  .annotation(EnumAnn.class, a -> a.arg("value", RetentionPolicy.CLASS));
		});
		var a = (EnumAnn)cls.getField("a").getDeclaredAnnotations()[0];
		assertThat(a.value()).isEqualTo(RetentionPolicy.CLASS);
	}
	
	@Test
	void fieldAnnotation() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(String.class, "testString")
			  .annotation(MultiAnn.class, ann -> ann.arg("value", 141).arg("lol", "xD"));
		});
		var anns = cls.getFields()[0].getDeclaredAnnotations();
		LogUtil.println((Object[])anns);
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
	}
	
	@Test
	void methodAnnotation() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test")
			  .annotation(MultiAnn.class, ann -> ann.arg("value", 141).arg("lol", "xD"))
			  .body();
		});
		var anns = cls.getMethod("test").getAnnotations();
		LogUtil.println((Object[])anns);
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
	}
	@Test
	void classAnnotation() throws Exception{
		var className = autoName();
		var cls = generateAndLoadInstance(className, cd -> {
			cd.name(ClassName.dotted(className))
			  .annotation(MultiAnn.class, ann -> ann.arg("value", 141).arg("lol", "xD"));
		});
		var anns = cls.getAnnotations();
		LogUtil.println((Object[])anns);
		assertThat(anns).hasSize(1);
		var ann = (MultiAnn)anns[0];
		assertThat(ann.lol()).isEqualTo("xD");
		assertThat(ann.value()).isEqualTo(141);
	}
	
	@Test
	void overrideClass() throws Exception{
		var className = autoName();
		var cls = generateAndLoadInstance(className, cd -> {
			cd.name(ClassName.dotted(className)).extendsType(ISayHello.class);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		var expected = new ISayHello().toString();
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(expected);
	}
	
	@Test
	void dummyClass() throws Exception{
		var msg = "Ayyyy it works!";
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("toString").returns(String.class).body().val(msg);
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(msg);
	}
	
	@SuppressWarnings("unchecked")
	@Test
	<T extends Enum<T>> void simpleEnum() throws Exception{
		
		var className = "com.lapissea.jorth.WtfIsMyEnumAAA";
		var cls = generateAndLoadInstance(className, cd -> {
			cd.name(ClassName.dotted(className))
			  .type(ClassType.ENUM);
			cd.enumConstant("FOO");
			cd.enumConstant("BAR");
		});
		cls.getEnumConstants();
		
		assertThat(EnumSet.allOf((Class<T>)cls).stream().map(Enum::name)).containsExactly("FOO", "BAR");
	}
	
	@Test
	void enumConstructorArguments() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			var code  = cd.field(int.class, "code").finalAcc();
			var label = cd.field(String.class, "label").finalAcc();
			// User parameters may have the same names as the JVM's implicit parameters.
			cd.instanceInit().arg(String.class, "name")
			  .arg(int.class, "ordinal").body()
			  .get("this").get("name").setField(label)
			  .get("this").get("ordinal").setField(code);
			cd.enumConstant("OK", c -> c.val("Success").val(200));
			cd.enumConstant("MISSING", c -> c.val("Not found").val(404));
		});
		var constants = cls.getEnumConstants();
		assertThat(constants).hasSize(2);
		for(int i = 0; i<constants.length; i++){
			var constant = (Enum<?>)constants[i];
			assertThat(constant.name()).isEqualTo(i == 0? "OK" : "MISSING");
			assertThat(constant.ordinal()).isEqualTo(i);
			assertThat(cls.getField("code").get(constant)).isEqualTo(i == 0? 200 : 404);
			assertThat(cls.getField("label").get(constant)).isEqualTo(i == 0? "Success" : "Not found");
		}
		assertThat(cls.getDeclaredConstructors()).hasSize(1);
		var constructor = cls.getDeclaredConstructors()[0];
		assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
		assertThat(constructor.getParameterTypes()).containsExactly(String.class, int.class, String.class, int.class);
	}
	
	@Test
	void enumConstructorOverloads() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			var value = cd.field(int.class, "value").finalAcc();
			cd.instanceInit().body()
			  .get("this")
			  .get("this").call("ordinal")
			  .setField(value);
			cd.instanceInit().arg(int.class, "value").body()
			  .get("this").get("value").setField(value);
			cd.instanceInit().arg(String.class, "value").body()
			  .get("this").get("value").call("length").setField(value);
			cd.enumConstant("DEFAULT");
			cd.enumConstant("INTEGER", c -> c.val(42));
			cd.enumConstant("STRING", c -> c.val("hello"));
		});
		var constants = cls.getEnumConstants();
		assertThat(cls.getField("value").get(constants[0])).isEqualTo(0);
		assertThat(cls.getField("value").get(constants[1])).isEqualTo(42);
		assertThat(cls.getField("value").get(constants[2])).isEqualTo(5);
		assertThat(cls.getDeclaredConstructors()).hasSize(3);
	}
	
	@Test
	void enumConstantsBeforeUserStaticInitialization() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			var snapshot = cd.field(new GenericType(cd.name()).arrayType(), "snapshot").staticFinal();
			// Declare this before the constants to ensure declaration order is irrelevant.
			cd.staticInit().body()
			  .call(cd.name(), "values")
			  .setField(snapshot);
			cd.enumConstant("FIRST");
			cd.enumConstant("SECOND");
		});
		assertThat((Object[])cls.getField("snapshot").get(null)).containsExactly(cls.getEnumConstants());
		var values = (Object[])cls.getMethod("values").invoke(null);
		values[0] = null;
		assertThat((Object[])cls.getMethod("values").invoke(null)).containsExactly(cls.getEnumConstants());
	}
	
	private static final AtomicInteger enumArgumentCalls = new AtomicInteger();
	public static int enumArgument(int value){
		enumArgumentCalls.incrementAndGet();
		return value;
	}
	
	@Test
	void enumArgumentExpressionsExecuteOnce() throws Exception{
		enumArgumentCalls.set(0);
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			var value = cd.field(int.class, "value").finalAcc();
			cd.instanceInit().arg(int.class, "value").body()
			  .get("this").get("value").setField(value);
			cd.enumConstant("FIRST", c -> c.call(JorthTests.class, "enumArgument", a -> a.val(11)));
			cd.enumConstant("SECOND", c -> c.call(JorthTests.class, "enumArgument", a -> a.val(22)));
		});
		assertThat(enumArgumentCalls.get()).isEqualTo(2);
		var constants = cls.getEnumConstants();
		assertThat(cls.getField("value").get(constants[0])).isEqualTo(11);
		assertThat(cls.getField("value").get(constants[1])).isEqualTo(22);
		cls.getMethod("values").invoke(null);
		cls.getMethod("values").invoke(null);
		assertThat(enumArgumentCalls.get()).isEqualTo(2);
	}
	
	@Test
	void enumWideConstructorArguments() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.type(ClassType.ENUM);
			var large    = cd.field(long.class, "large").finalAcc();
			var fraction = cd.field(double.class, "fraction").finalAcc();
			cd.instanceInit().arg(long.class, "$enum$name")
			  .arg(double.class, "$enum$ordinal")
			  .body()
			  .get("this").get("$enum$name").setField(large)
			  .get("this").get("$enum$ordinal").setField(fraction);
			cd.enumConstant("FIRST", c -> c.val(1234567890123L).val(1.25));
			cd.enumConstant("SECOND", c -> c.val(-9876543210987L).val(-2.5));
		});
		var constants = cls.getEnumConstants();
		assertThat(cls.getField("large").get(constants[0])).isEqualTo(1234567890123L);
		assertThat(cls.getField("large").get(constants[1])).isEqualTo(-9876543210987L);
		assertThat(cls.getField("fraction").get(constants[0])).isEqualTo(1.25);
		assertThat(cls.getField("fraction").get(constants[1])).isEqualTo(-2.5);
		assertThat(((Enum<?>)constants[0]).name()).isEqualTo("FIRST");
		assertThat(((Enum<?>)constants[1]).ordinal()).isEqualTo(1);
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsExplicitConstructorInvocation() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().body()
		  .get("this").call("<init>", c -> c.val("ILLEGAL").val(0));
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsMissingConstructorMatch() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().arg(int.class, "value").body();
		cd.enumConstant("MISSING");
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsWrongConstructorArgument() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().arg(int.class, "value").body();
		cd.enumConstant("WRONG", c -> c.val("text"));
	}
	
	@Test
	void emptyEnum() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> cd.type(ClassType.ENUM));
		assertThat(cls.getEnumConstants()).isEmpty();
		assertThat((Object[])cls.getMethod("values").invoke(null)).isEmpty();
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsDuplicateConstructor() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().arg(int.class, "first").body();
		cd.instanceInit().arg(int.class, "second").body();
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsConstructorDeclaredAfterConstant() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.enumConstant("FIRST");
		cd.instanceInit().arg(int.class, "value").body();
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsConstructorBodyDefinedAfterConstant() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().body();
		cd.instanceInit().arg(int.class, "value");
		cd.enumConstant("FIRST");
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsDuplicateConstants() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.enumConstant("SAME");
		cd.enumConstant("SAME");
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsDirectConstruction() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.enumConstant("ONLY");
		cd.function("make").staticAcc().returns(new GenericType(cd.name())).body()
		  .newObj(cd.name(), c -> c.val("ILLEGAL").val(0));
	}
	
	@Test(expectedExceptions = MalformedJorth.class)
	void enumRejectsExplicitSuperclassCall() throws Exception{
		var cd = new ClassDefinition(null).name(ClassName.dotted(autoName())).type(ClassType.ENUM);
		cd.instanceInit().body().callSuper(c -> c.val("ILLEGAL").val(0));
	}
	
	@Test
	<T extends Enum<T>> void simpleInterface() throws Exception{
		var className = autoName();
		var cls = generateAndLoadInstance(className, cd -> {
			cd.type(ClassType.INTERFACE).name(ClassName.dotted(className));
			cd.function("hello").returns(String.class);
		});
		assertThat(cls).isInterface();
		var hello = cls.getMethod("hello");
		LogUtil.println(hello);
		assertThat(Modifier.isAbstract(hello.getModifiers())).as("Method should be abstract").isTrue();
	}
	
	@Test
	void getClassRef() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("getCls").staticAcc().returns(Class.class, String.class)
			  .body().val(String.class);
		});
		
		var expected = String.class;
		var actual   = cls.getMethod("getCls").invoke(null);
		
		assertThat(actual).isEqualTo(expected);
	}
	@Test
	void superArgs() throws Exception{
		var className   = autoName();
		var expectedStr = "Hi from super";
		var cls = generateAndLoadInstance(className, cd -> {
			cd.name(ClassName.dotted(className)).extendsType(IStoreHello.class);
			cd.instanceInit()
			  .body()
			  .callSuper(c -> c.val(expectedStr));
		});
		
		var constr = cls.getConstructor();
		var inst   = constr.newInstance();
		
		LogUtil.println(cls, "says", inst);
		assertThat(inst).asString().isEqualTo(expectedStr);
	}
	
	@Test
	void genericFieldDefine() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.field(GenericType.of(Optional.class).withArgs(String.class), "optStr");
		});
		
		var generic = (ParameterizedType)cls.getField("optStr").getGenericType();
		
		assertThat(generic.getRawType()).isEqualTo(Optional.class);
		assertThat(generic.getActualTypeArguments()).containsExactly(String.class);
	}
	
	@Test(dependsOnMethods = "genericFieldDefine")
	void genericField() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			var optStr = cd.field(GenericType.of(Optional.class).withArgs(String.class), "optStr");
			
			cd.function("set")
			  .arg(GenericType.of(Optional.class).withArgs(String.class), "optStr")
			  .body()
			  .get("optStr")
			  .setThis(optStr);
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
		
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("upper")
			  .arg(GenericType.of(List.class).withArgs(JType.upper(Typ.class)), "arg").body();
			cd.function("lower")
			  .arg(GenericType.of(List.class).withArgs(JType.lower(Typ.class)), "arg").body();
			cd.function("wild")
			  .arg(GenericType.of(List.class).withArgs(JType.WILDCARD), "arg").body();
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
		var cls = generateAndLoadInstanceMulti(List.of("SealedClass", "child1", "child2"), (name, cd) -> {
			cd.name(ClassName.dotted(name));
			switch(name){
				case "SealedClass" -> {
					cd.permits(ClassName.dotted("child1")).permits(ClassName.dotted("child2"));
				}
				case "child1", "child2" -> {
					cd.finalAcc().extendsType(ClassName.dotted("SealedClass"));
				}
			}
		});
		
		var permits = cls.getPermittedSubclasses();
		assertThat(permits).isNotNull();
		assertThat(Arrays.stream(permits).map(Class::getName)).containsExactlyInAnyOrder("child1", "child2");
	}
	
	@Test
	void parmClass() throws Exception{
		var cls = generateAndLoadInstance("ParmClass", cd -> {
			cd.name(ClassName.dotted("ParmClass")).genericArg(CharSequence.class, "T");
		});
		
		var parms = cls.getTypeParameters();
		assertThat(parms).hasSize(1);
		var parm = parms[0];
		assertThat(parm.getName()).isEqualTo("T");
		assertThat(parm.getBounds()).containsExactly(CharSequence.class);
	}
	
	@Test(dependsOnMethods = "parmClass")
	void parmClassFun() throws Exception{
		var cls = generateAndLoadInstance("ParmClass", cd -> {
			cd.name(ClassName.dotted("ParmClass")).genericArg(CharSequence.class, "T");
			
			cd.function("takeArg")
			  .arg(GenericType.of(List.class).withArgs(cd.getArg("T")), "tList")
			  .body();
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
		var cls = generateAndLoadInstance("ParmClass", cd -> {
			cd.name(ClassName.dotted("ParmClass")).genericArg(CharSequence.class, "T");
			cd.field(cd.getArg("T"), "arg");
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
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name));
			
			for(Prop prop : props){
				cd.field(prop.type, prop.name);
			}
			var body = cd.instanceInit().body().callSuperAutoPass();
			for(Prop prop : props){
				switch(prop.defaultVal){
					case Integer v -> body.val(v);
					case Float v -> body.val(v);
					default -> throw new NotImplementedException(prop.defaultVal.getClass().getTypeName());
				}
				body.setThis(cd.getField(prop.name));
			}
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
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name));
			for(String s : names){
				cd.field(int.class, s);
			}
		});
		
		assertThat(Arrays.stream(cls.getFields()).map(Field::getName)).containsExactlyInAnyOrderElementsOf(names);
	}
	
	public static final class TestBootstrap{
		public static CallSite bootstrap(MethodHandles.Lookup lookup,
		                                 String name,
		                                 MethodType type,
		                                 Class<?> passType) throws Throwable{
			var cname = lookup.lookupClass().getName() + "&_" + name;
			
			var cd = new ClassDefinition(null);
			cd.name(ClassName.dotted(cname));
			cd.function(name).staticAcc().arg(int.class, "num").returns(String.class)
			  .body()
			  .newObj(StringBuilder.class, c -> c.val(passType.getName() + " "))
			  .call("append", c -> c.get("num"))
			  .call("toString");
			
			byte[]               bb        = cd.getClassFile();
			MethodHandles.Lookup implClass = lookup.defineHiddenClass(bb, true);
			MethodHandle         target    = implClass.unreflect(implClass.lookupClass().getMethod(name, int.class));
			return new ConstantCallSite(target);
		}
		
	}
	
	@Test
	void virtualCall() throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name)).implement(GenericType.of(IntFunction.class).withArgs(String.class));
			cd.function("apply").arg(int.class, "num").returns(Object.class).annotation(Override.class)
			  .body()
			  .callVirtual(b -> b.caller(TestBootstrap.class, "bootstrap").arg(Class.class, ClassName.dotted(name)),
			               fn -> fn.name("makeString").arg(int.class).returns(String.class),
			               c -> c.get("num"));
		});
		
		Object instO = cls.getConstructor().newInstance();
		
		assertThat(instO).isInstanceOf(IntFunction.class);
		//noinspection unchecked
		IntFunction<String> inst = (IntFunction<String>)instO;
		assertThat(inst.apply(1)).isEqualTo(name + " 1");
		assertThat(inst.apply(69)).isEqualTo(name + " 69");
	}
	@Test(dependsOnMethods = "simpleInterface")
	void incrementInt() throws Exception{
		var inst = generateInterface(autoName(), INT_UNARY_OPERATOR, cb -> {
			cb.get("num")
			  .add(2);
		});
		
		assertThat(inst.applyAsInt(10)).isEqualTo(12);
		assertThat(inst.applyAsInt(-2)).isEqualTo(0);
	}
	@Test(dependsOnMethods = "simpleInterface")
	void incrementDouble() throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name)).implement(DoubleUnaryOperator.class);
			cd.function("applyAsDouble").arg(double.class, "num").override()
			  .body()
			  .get("num")
			  .add(2.125);
		});
		
		Object instO = cls.getConstructor().newInstance();
		
		assertThat(instO).isInstanceOf(DoubleUnaryOperator.class);
		var inst = (DoubleUnaryOperator)instO;
		assertThat(inst.applyAsDouble(10)).isEqualTo(12.125);
		assertThat(inst.applyAsDouble(-2)).isEqualTo(0.125);
	}
	
	@Test(dependsOnMethods = "incrementInt")
	void variables() throws Exception{
		var name = autoName();
		var cls = generateAndLoadInstance(name, cd -> {
			cd.name(ClassName.dotted(name)).implement(IntSupplier.class);
			cd.function("getAsInt").override()
			  .body()
			  .var(int.class, "a")
			  .var(int.class, "b")
			  .set("a", 1)
			  .set("b", 2)
			  .get("a")
			  .add(4);
		});
		
		Object instO = cls.getConstructor().newInstance();
		
		assertThat(instO).isInstanceOf(IntSupplier.class);
		var inst = (IntSupplier)instO;
		assertThat(inst.getAsInt()).isEqualTo(5);
	}
	
	@Test(dependsOnMethods = "simpleInterface")
	void bitShiftRight() throws Exception{
		var inst = generateInterface(autoName(), INT_UNARY_OPERATOR, cb -> {
			cb.get("num")
			  .bitShiftRight(false, 2);
		});
		assertThat(inst.applyAsInt(10)).isEqualTo(10>>2);
		assertThat(inst.applyAsInt(-2)).isEqualTo(-2>>2);
	}
	@Test(dependsOnMethods = "simpleInterface")
	void bitShiftRightLogical() throws Exception{
		var inst = generateInterface(autoName(), INT_UNARY_OPERATOR, cb -> {
			cb.get("num")
			  .bitShiftRight(true, 2);
		});
		assertThat(inst.applyAsInt(10)).isEqualTo(10 >>> 2);
		assertThat(inst.applyAsInt(-2)).isEqualTo(-2 >>> 2);
	}
	
	@Test(dependsOnMethods = "simpleInterface")
	void bitShiftLeft() throws Exception{
		var inst = generateInterface(autoName(), INT_UNARY_OPERATOR, cb -> {
			cb.get("num")
			  .bitShiftLeft(2);
		});
		assertThat(inst.applyAsInt(10)).isEqualTo(10<<2);
		assertThat(inst.applyAsInt(-2)).isEqualTo(-2<<2);
	}
	
	@Test(dependsOnMethods = "simpleInterface")
	void bitAnd() throws Exception{
		var inst = generateInterface(autoName(), INT_UNARY_OPERATOR, cb -> {
			cb.get("num")
			  .bitAnd(5);
		});
		assertThat(inst.applyAsInt(7)).isEqualTo(7&5);
		assertThat(inst.applyAsInt(-2)).isEqualTo(-2&5);
	}
	
	@Test(dependsOnMethods = "simpleInterface")
	void nullVal() throws Exception{
		var    inst = generateInterface(autoName(), STRING_SUPPLIER, cb -> cb.nullVal(String.class));
		String res  = inst.get();
		assertThat(res).isNull();
	}
	
	@Test(dependsOnMethods = "simpleInterface", expectedExceptions = MissingLocalField.class)
	void forgetLocalA() throws Exception{
		var inst = generateInterface(autoName(), INT_SUPPLIER, cb -> cb.var(int.class, "a")
		                                                               .set("a", 1)
		                                                               .forgetVar("a")
		                                                               .get("a"));
		inst.getAsInt();
	}
	@Test(dependsOnMethods = "simpleInterface", expectedExceptions = MissingLocalField.class)
	void forgetLocalB() throws Exception{
		var inst = generateInterface(autoName(), INT_SUPPLIER, cb -> cb.scope(code -> {
			code.var(int.class, "a")
			    .set("a", 1);
		}).get("a"));
		inst.getAsInt();
	}
	@Test(dependsOnMethods = "simpleInterface")
	void forgetLocalThen() throws Exception{
		var inst = generateInterface(autoName(), INT_SUPPLIER, cb -> {
			cb.var(int.class, "a")
			  .set("a", 1)
			  .scope(code -> {
				  code.var(int.class, "b")
				      .set("b", 2);
			  })
			  .var(int.class, "c")
			  .set("c", 3)
			  .get("c");
		});
		int res = inst.getAsInt();
		assertThat(res).isEqualTo(3);
	}
}
