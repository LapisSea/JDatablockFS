package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static com.lapissea.jorth.TestUtils.autoName;
import static com.lapissea.jorth.TestUtils.generateAndLoadInstanceSimple;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Missing functional coverage (MT items) for core CodeBlock operations:
 * box/unbox, cast, array element access, throwOp, ifFalse/ifIsNull/ifIsNotNull,
 * dup/swap, setIntoNewVar, equalityOp and 2-slot locals.
 */
public class JorthCoverageCoreTests{
	
	// ------------------------------------------------------------------ helpers
	
	/**
	 * Builds the class file without loading it, so emitted opcodes can be inspected.
	 */
	private static byte[] generateBytes(UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(autoName()));
		generator.accept(cd);
		return cd.getClassFile();
	}
	
	/**
	 * Collects the opcodes of one method of a generated class file (ASM ClassReader).
	 */
	private static List<Integer> collectOpcodes(byte[] bytes, String methodName){
		var ops = new ArrayList<Integer>();
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
				if(!name.equals(methodName)) return null;
				return new MethodVisitor(Opcodes.ASM9){
					@Override
					public void visitInsn(int opcode){
						ops.add(opcode);
					}
					@Override
					public void visitIntInsn(int opcode, int operand){
						ops.add(opcode);
					}
					@Override
					public void visitTypeInsn(int opcode, String type){
						ops.add(opcode);
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		return ops;
	}
	
	// ------------------------------------------------------------------ MT-01
	
	@DataProvider
	Object[][] wrapperRows(){
		return new Object[][]{
			{Boolean.class, boolean.class, Boolean.TRUE},
			{Byte.class, byte.class, (byte)7},
			{Short.class, short.class, (short)123},
			{Integer.class, int.class, 42},
			{Long.class, long.class, 99L},
			{Character.class, char.class, 'x'},
			{Float.class, float.class, 1.5f},
			{Double.class, double.class, 2.25},
			};
	}
	
	// MT-01 — box()/unbox() for all 8 wrappers: pins the valueOf/xxxValue method-name maps
	@Test(dataProvider = "wrapperRows")
	void mt01_boxUnbox(Class<?> wrapper, Class<?> primitive, Object sample) throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("unbox").staticAcc().arg(wrapper, "x").returns(primitive)
			  .body().get("x").unbox();
			cd.function("box").staticAcc().arg(primitive, "x").returns(wrapper)
			  .body().get("x").box();
		});
		
		var unbox = cls.getMethod("unbox", wrapper);
		var box   = cls.getMethod("box", primitive);
		
		assertThat(unbox.invoke(null, sample)).as("unbox of " + sample).isEqualTo(sample);
		assertThat(box.invoke(null, sample)).as("box of " + sample).isEqualTo(sample);
		assertThat(unbox.invoke(null, box.invoke(null, sample))).as("round-trip of " + sample).isEqualTo(sample);
	}
	
	// ------------------------------------------------------------------ MT-02
	
	// MT-02 — cast(): CHECKCAST for Object->String and Object->String[] (array downcast)
	@Test
	void mt02_castReference() throws Exception{
		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>)cd -> {
			cd.function("toUpper").staticAcc().arg(Object.class, "o").returns(String.class)
			  .body().get("o").cast(String.class).call("toUpperCase");
			cd.function("first").staticAcc().arg(Object.class, "o").returns(String.class)
			  .body().get("o").cast(String[].class).val(0).getArrayElement();
		};
		var bytes = generateBytes(builder);
		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
		
		assertThat(collectOpcodes(bytes, "toUpper")).as("Object->String cast must emit CHECKCAST").contains(Opcodes.CHECKCAST);
		
		assertThat(cls.getMethod("toUpper", Object.class).invoke(null, "hello")).isEqualTo("HELLO");
		String[] arr = {"first", "second"};
		assertThat(cls.getMethod("first", Object.class).invoke(null, (Object)arr)).isEqualTo("first");
	}
	
	@DataProvider
	Object[][] castRows(){
		return new Object[][]{
			{5, long.class, 5L},
			{5, float.class, 5.0f},
			{5, double.class, 5.0},
			{42L, int.class, 42},
			{1.5f, double.class, 1.5},
			{2.75, int.class, 2},
			};
	}
	
	// MT-02 — cast() primitive->primitive (PrimitiveCastOp): representative conversion pairs
	@Test(dataProvider = "castRows")
	void mt02_primitiveCast(Object from, Class<?> to, Object expected) throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("conv").staticAcc().returns(to)
			  .body().val(from).cast(to);
		});
		
		assertThat(cls.getMethod("conv").invoke(null)).isEqualTo(expected);
	}
	
	// MT-02 — cast(): Object holding a String cast to Integer -> runtime ClassCastException
	@Test
	void mt02_castRuntimeNegative() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("badCast").staticAcc().arg(Object.class, "o").returns(Integer.class)
			  .body().get("o").cast(Integer.class);
		});
		
		try{
			cls.getMethod("badCast", Object.class).invoke(null, "notAnInt");
			assertThat(false).as("Cast must have failed at runtime").isTrue();
		}catch(InvocationTargetException e){
			assertThat(e.getCause()).isInstanceOf(ClassCastException.class);
		}
	}
	
	// ------------------------------------------------------------------ MT-03
	
	@DataProvider
	Object[][] componentRows(){
		return new Object[][]{
			{boolean.class, Boolean.TRUE},
			{byte.class, (byte)7},
			{char.class, 'x'},
			{short.class, (short)123},
			{int.class, 42},
			{long.class, 99L},
			{float.class, 1.5f},
			{double.class, 2.25},
			{String.class, "hello"},
			};
	}
	
	// MT-03 — setArrayElement()/getArrayElement() for every component type (arrays passed as parameters)
	@Test(dataProvider = "componentRows")
	void mt03_arrayElements(Class<?> component, Object value) throws Exception{
		Class<?> arrayType = Array.newInstance(component, 0).getClass();
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("rt").staticAcc()
			  .arg(arrayType, "arr").arg(component, "val").returns(component)
			  .body()
			  .get("arr").val(0).get("val").setArrayElement()
			  .get("arr").val(0).getArrayElement();
		});
		
		Object arr = Array.newInstance(component, 3);
		var    fn  = cls.getMethod("rt", arrayType, component);
		assertThat(fn.invoke(null, arr, value)).isEqualTo(value);
		assertThat(Array.get(arr, 0)).isEqualTo(value);
	}
	
	@DataProvider
	Object[][] indexStoreRows(){
		return new Object[][]{
			{byte.class, (byte)9},
			{short.class, (short)1234},
			};
	}
	
	// MT-03 — byte/short index on store is accepted (arrayIndexCompatible) and stores correctly
	@Test(dataProvider = "indexStoreRows")
	void mt03_byteShortIndexStore(Class<?> indexType, Object value) throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("store").staticAcc()
			  .arg(indexType.arrayType(), "arr").arg(indexType, "idx").arg(indexType, "val").returns(indexType)
			  .body()
			  .get("arr").get("idx").get("val").setArrayElement()
			  .get("arr").val(0).getArrayElement();
		});
		
		Object arr = Array.newInstance(indexType, 3);
		var    fn  = cls.getMethod("store", indexType.arrayType(), indexType, indexType);
		var    idx = indexType == byte.class? (Object)(byte)0 : (Object)(short)0;
		assertThat(fn.invoke(null, arr, idx, value)).isEqualTo(value);
		assertThat(Array.get(arr, 0)).isEqualTo(value);
	}
	
	// MT-03 — char-typed index is rejected at codegen (BaseType.CHAR arrayIndexCompatible=false false negative)
	@Test
	void mt03_charIndexStoreRejected() throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("store").staticAcc()
			  .arg(char[].class, "arr").arg(char.class, "idx").arg(char.class, "val")
			  .body()
			  .get("arr").get("idx").get("val").setArrayElement();
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("can not be used as array index");
	}
	
	@DataProvider
	Object[][] indexLoadRejectRows(){
		return new Object[][]{
			{char.class, "char"},
			{byte.class, "byte"},
			{short.class, "short"},
			};
	}
	
	// MT-03 — byte/short/char index on load is rejected (GetElementOp requires exactly int): pins the load/store asymmetry
	@Test(dataProvider = "indexLoadRejectRows")
	void mt03_byteShortCharIndexLoadRejected(Class<?> indexType, String typeName) throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("load").staticAcc()
			  .arg(indexType.arrayType(), "arr").arg(indexType, "idx").returns(indexType)
			  .body()
			  .get("arr").get("idx").getArrayElement();
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("The index of the array element must be an integer but is: " + typeName);
	}
	
	// ------------------------------------------------------------------ MT-05
	
	// MT-05 — throwOp(): ATHROW of a constructed exception, message preserved
	@Test
	void mt05_throwOp() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("boom").staticAcc().body()
			  .newObj(RuntimeException.class, c -> c.val("boom"))
			  .throwOp();
		});
		
		try{
			cls.getMethod("boom").invoke(null);
			assertThat(false).as("Method must have thrown").isTrue();
		}catch(InvocationTargetException e){
			assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
			assertThat((RuntimeException)e.getCause()).hasMessage("boom");
		}
	}
	
	// MT-05 — throwOp() with a non-Throwable on the stack is rejected at codegen
	@Test
	void mt05_throwOpNonThrowableRejected() throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("bad").staticAcc().body()
			  .val(1).throwOp();
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("is on stack");
	}
	
	// MT-05 — throwOp() with an empty stack is rejected at codegen
	@Test
	void mt05_throwOpEmptyStackRejected() throws Exception{
		assertThatThrownBy(() -> generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("bad").staticAcc().body()
			  .throwOp();
		})).isInstanceOf(MalformedJorth.class)
		   .hasMessageContaining("element");
	}
	
	// ------------------------------------------------------------------ MT-06
	
	// MT-06 — ifFalse(boolean) covers the else-less conditional path; both outcomes asserted
	@Test
	void mt06_ifFalse() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().arg(boolean.class, "b").returns(int.class)
			  .body()
			  .get("b")
			  .ifFalse(code -> code.val(1).returnOp())
			  .val(2);
		});
		
		var fn = cls.getMethod("test", boolean.class);
		assertThat(fn.invoke(null, true)).as("b=true: false-branch skipped").isEqualTo(2);
		assertThat(fn.invoke(null, false)).as("b=false: false-branch taken").isEqualTo(1);
	}
	
	// MT-06 — ifIsNull(String): null vs non-null select the branch (synthetic nullVal + ifEquality)
	@Test
	void mt06_ifIsNull() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().arg(String.class, "s").returns(int.class)
			  .body()
			  .get("s")
			  .ifIsNull(code -> code.val(1).returnOp())
			  .val(2);
		});
		
		var fn = cls.getMethod("test", String.class);
		assertThat(fn.invoke(null, new Object[]{null})).as("null input").isEqualTo(1);
		assertThat(fn.invoke(null, "x")).as("non-null input").isEqualTo(2);
	}
	
	// MT-06 — ifIsNotNull(String): null vs non-null select the branch (synthetic nullVal + ifNotEquality)
	@Test
	void mt06_ifIsNotNull() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().arg(String.class, "s").returns(int.class)
			  .body()
			  .get("s")
			  .ifIsNotNull(code -> code.val(1).returnOp())
			  .val(2);
		});
		
		var fn = cls.getMethod("test", String.class);
		assertThat(fn.invoke(null, new Object[]{null})).as("null input").isEqualTo(2);
		assertThat(fn.invoke(null, "x")).as("non-null input").isEqualTo(1);
	}
	
	// ------------------------------------------------------------------ MT-07
	
	// MT-07 — dup(): both copies of a dup'd reference are usable (s+s); DUP2 on a long top
	@Test
	void mt07_dup() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("doubleStr").staticAcc().arg(String.class, "s").returns(String.class)
			  .body().get("s").dup().call("concat", c -> c.get("s"));
			cd.function("dupLong").staticAcc().returns(long.class)
			  .body().val(5L).dup().pop();
		});
		
		assertThat(cls.getMethod("doubleStr", String.class).invoke(null, "ab")).isEqualTo("abab");
		assertThat(cls.getMethod("dupLong").invoke(null)).isEqualTo(5L);
	}
	
	// MT-07 — swap(): 1-slot SWAP and the 2-slot DUP2_X1+POP2 / DUP2_X2+POP2 emission sequences
	@Test
	void mt07_swap() throws Exception{
		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>)cd -> {
			var m = cd.function("m").staticAcc().arg(int.class, "a").arg(int.class, "b").returns(int.class);
			m.body().get("a");
			cd.function("test").staticAcc().returns(int.class)
			  .body().val(1).val(2).swap().call(m);
			
			var mL = cd.function("mL").staticAcc().arg(long.class, "a").arg(int.class, "b").returns(long.class);
			mL.body().get("a");
			cd.function("testL").staticAcc().returns(long.class)
			  .body().val(1).val(2L).swap().call(mL);
			
			var mD = cd.function("mD").staticAcc().arg(double.class, "a").arg(double.class, "b").returns(double.class);
			mD.body().get("a");
			cd.function("testD").staticAcc().returns(double.class)
			  .body().val(1.5).val(2.25).swap().call(mD);
		};
		var bytes = generateBytes(builder);
		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
		
		// without the swap the first parameter would be the bottom-of-stack value
		assertThat(cls.getMethod("test").invoke(null)).as("int/int swap flipped arg order").isEqualTo(2);
		assertThat(cls.getMethod("testL").invoke(null)).as("long-top swap flipped arg order").isEqualTo(2L);
		assertThat(cls.getMethod("testD").invoke(null)).as("double-top swap flipped arg order").isEqualTo(2.25);
		
		assertThat(collectOpcodes(bytes, "test")).as("1-slot/1-slot swap emits SWAP").contains(Opcodes.SWAP);
		assertThat(collectOpcodes(bytes, "testL")).as("long-top swap emits DUP2_X1+POP2").contains(Opcodes.DUP2_X1, Opcodes.POP2);
		assertThat(collectOpcodes(bytes, "testD")).as("double-top swap emits DUP2_X2+POP2").contains(Opcodes.DUP2_X2, Opcodes.POP2);
	}
	
	// MT-07 — swap() with int over double (1-slot top over 2-slot below): exercises the DUP_X2+POP emission path
	@Test
	void mt07_swapIntOverDouble() throws Exception{
		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>)cd -> {
			var m = cd.function("m").staticAcc().arg(int.class, "a").arg(double.class, "b").returns(int.class);
			m.body().get("a");
			cd.function("test").staticAcc().returns(int.class)
			  .body().val(1.5).val(2).swap().call(m);
		};
		var bytes = generateBytes(builder);
		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
		
		assertThat(cls.getMethod("test").invoke(null)).as("int-over-double swap flipped arg order").isEqualTo(2);
		assertThat(collectOpcodes(bytes, "test")).as("1-slot top over 2-slot below emits DUP_X2+POP").contains(Opcodes.DUP_X2, Opcodes.POP);
	}
	
	// ------------------------------------------------------------------ MT-08
	
	// MT-08 — setIntoNewVar(String): one-call declare+store, value readable back
	@Test
	void mt08_setIntoNewVar() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().returns(int.class)
			  .body().val(42).setIntoNewVar("x").get("x");
		});
		
		assertThat(cls.getMethod("test").invoke(null)).isEqualTo(42);
	}
	
	// MT-08 — setIntoNewVar copies a local; forgetVar frees the slot and a new var reuses it without corrupting values
	@Test
	void mt08_setIntoNewVarCopyForgetReuse() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("test").staticAcc().arg(int.class, "p").returns(String.class)
			  .body()
			  .var(int.class, "a")
			  .get("p").set("a")
			  .get("a")
			  .setIntoNewVar("b")
			  .forgetVar("a")
			  .var(int.class, "c")
			  .set("c", 10000)
			  .newObj(StringBuilder.class)
			  .call("append", c -> c.get("b"))
			  .call("append", c -> c.val(" "))
			  .call("append", c -> c.get("c"))
			  .call("toString");
		});
		
		assertThat(cls.getMethod("test", int.class).invoke(null, 42)).isEqualTo("42 10000");
	}
	
	// ------------------------------------------------------------------ MT-12
	
	@DataProvider
	Object[][] equalityRows(){
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
	
	// MT-12 — equalityOp() for all 9 types (body: get a, get b, ==)
	@Test(dataProvider = "equalityRows")
	void mt12_equalityOp(Class<?> type, Object arg1, Object arg2, boolean expectedEqual) throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("eq").staticAcc()
			  .arg(type, "a").arg(type, "b").returns(boolean.class)
			  .body().get("a").get("b").equalityOp();
		});
		
		var fn = cls.getMethod("eq", type, type);
		assertThat(fn.invoke(null, arg1, arg2)).isEqualTo(expectedEqual);
	}
	
	// ------------------------------------------------------------------ MT-13
	
	// MT-13 — long/double 2-slot locals interleaved with int locals: slot packing must not corrupt neighbours;
	// the long is allocated at the first free 2-slot pair (slots 2-3), never across the occupied slot 1
	@Test
	void mt13_twoSlotLocals() throws Exception{
		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
			cd.function("pack").staticAcc().arg(int.class, "a").returns(String.class)
			  .body()
			  .var(int.class, "i1")
			  .var(long.class, "l")
			  .var(int.class, "i2")
			  .var(double.class, "d")
			  .get("a").add(1).set("i1")
			  .set("l", 12345L)
			  .set("i2", 7)
			  .set("d", 2.75)
			  .newObj(StringBuilder.class)
			  .call("append", c -> c.get("i1"))
			  .call("append", c -> c.val(" "))
			  .call("append", c -> c.get("l"))
			  .call("append", c -> c.val(" "))
			  .call("append", c -> c.get("i2"))
			  .call("append", c -> c.val(" "))
			  .call("append", c -> c.get("d"))
			  .call("toString");
		});
		
		// param a@0, i1@1, l@2-3 (first free pair, not across slot 1), i2@4 (skips slot 3), d@5-6
		assertThat(cls.getMethod("pack", int.class).invoke(null, 5)).isEqualTo("6 12345 7 2.75");
	}
}
