package com.lapissea.jorth;

import com.lapissea.jorth.exceptions.IllegalClassState;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.jorth.lang.type.Visibility;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static com.lapissea.jorth.TestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Missing functional coverage (MT items) for the Jorth DSL:
 * enum generation with fields, remaining store overloads (local/field/static),
 * long arithmetic/bitwise, nested control flow and scope value propagation,
 * varargs, throwsException, visibility and access flags.
 */
public class JorthCoverageStoreArithTests{
	
	/** Mutable static target for the PUTSTATIC/GETSTATIC round-trip tests. */
	public static int mutableCounter;
	
	// ------------------------------------------------------------------ helpers
 	
 	/** Builds the class file without loading it, so emitted opcodes can be inspected. */
 	private static byte[] generateBytes(UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws MalformedJorth{
 		var cd = new ClassDefinition(null);
 		cd.name(ClassName.dotted(autoName()));
 		generator.accept(cd);
 		return cd.getClassFile();
 	}
 	
 	/** Collects the opcodes of one method of a generated class file (ASM ClassReader). */
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
 					@Override
 					public void visitVarInsn(int opcode, int var){
 						ops.add(opcode);
 					}
 					@Override
 					public void visitFieldInsn(int opcode, String owner, String name, String descriptor){
 						ops.add(opcode);
 					}
 					@Override
 					public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface){
 						ops.add(opcode);
 					}
 				};
 			}
 		}, ClassReader.SKIP_FRAMES);
 		return ops;
 	}
 	
 	/** Collects the access flags of one method of a generated class file (ASM ClassReader). */
 	private static int methodAccess(byte[] bytes, String methodName){
 		var access = new int[]{0};
 		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
 			@Override
 			public MethodVisitor visitMethod(int acc, String name, String descriptor, String signature, String[] exceptions){
 				if(!name.equals(methodName)) return null;
 				access[0] = acc;
 				return null;
 			}
 		}, ClassReader.SKIP_CODE);
 		return access[0];
 	}
 	
 	/** Collects the access flags of one field of a generated class file (ASM ClassReader). */
 	private static int fieldAccess(byte[] bytes, String fieldName){
 		var access = new int[]{0};
 		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
 			@Override
 			public FieldVisitor visitField(int acc, String name, String descriptor, String signature, Object value){
 				if(!name.equals(fieldName)) return null;
 				access[0] = acc;
 				return null;
 			}
 		}, ClassReader.SKIP_CODE);
 		return access[0];
 	}
 	
 	/** Collects the declared exceptions (Exceptions attribute) of one method (ASM ClassReader). */
 	private static List<String> collectExceptions(byte[] bytes, String methodName){
 		var res = new ArrayList<String>();
 		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9){
 			@Override
 			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
 				if(!name.equals(methodName)) return null;
 				if(exceptions != null) res.addAll(List.of(exceptions));
 				return null;
 			}
 		}, ClassReader.SKIP_CODE);
 		return res;
 	}
 	
 	// ------------------------------------------------------------------ MT-10
 	
 	// MT-10 — enum with ctor args + fields: pins the <clinit>/$VALUES wiring and the implicit (String,int) ctor
 	@Test
 	void mt10_enumWithConstructorArgsAndFields() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.type(ClassType.ENUM);
 			var number = cd.field(int.class, "number").visibility(Visibility.PRIVATE).finalAcc();
 			var text   = cd.field(String.class, "text").visibility(Visibility.PRIVATE).finalAcc();
 			cd.enumConstant("FOO");
 			cd.enumConstant("BAR");
 			cd.instanceInit().arg(String.class, "name").arg(int.class, "ordinal").body()
 			  .get("ordinal").val(0).ifEquality(c -> c.get("this").dup().set(number, 69).set(text, "foo :D"))
 			  .elseRun(c -> c.get("this").dup().set(number, 420).set(text, "bar :3"));
 			cd.function("getNumber").returns(int.class).body().getThis(number);
 			cd.function("getText").returns(String.class).body().getThis(text);
 		});
 		
 		var vals = cls.getEnumConstants();
 		assertThat(vals).hasSize(2);
 		assertThat(cls.getMethod("name").invoke(vals[0])).isEqualTo("FOO");
 		assertThat(cls.getMethod("name").invoke(vals[1])).isEqualTo("BAR");
 		assertThat(cls.getMethod("ordinal").invoke(vals[0])).isEqualTo(0);
 		assertThat(cls.getMethod("ordinal").invoke(vals[1])).isEqualTo(1);
 		assertThat(cls.getMethod("getNumber").invoke(vals[0])).isEqualTo(69);
 		assertThat(cls.getMethod("getText").invoke(vals[0])).isEqualTo("foo :D");
 		assertThat(cls.getMethod("getNumber").invoke(vals[1])).isEqualTo(420);
 		assertThat(cls.getMethod("getText").invoke(vals[1])).isEqualTo("bar :3");
 		
 		var values = (Object[])cls.getMethod("values").invoke(null);
 		assertThat(values).containsExactly(vals[0], vals[1]);
 		
 		var fooMods = cls.getDeclaredField("FOO").getModifiers();
 		assertThat(fooMods & (Modifier.STATIC | Modifier.FINAL | Opcodes.ACC_ENUM))
 		  .as("enum constant must be static final enum")
 		  .isEqualTo(Modifier.STATIC | Modifier.FINAL | Opcodes.ACC_ENUM);
 	}
 	
 	// ------------------------------------------------------------------ MT-14
 	
 	// MT-14 — set(String) store-top for double/long/String: pins DSTORE/LSTORE/ASTORE emission
 	@Test
 	void mt14_setStringStoreTop() throws Exception{
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			cd.function("test").staticAcc().returns(String.class)
 			  .body()
 			  .var(double.class, "d")
 			  .var(long.class, "l")
 			  .var(String.class, "s")
 			  .val(1.5).set("d")
 			  .val(5L).set("l")
 			  .val("str").set("s")
 			  .newObj(StringBuilder.class)
 			  .call("append", c -> c.get("d"))
 			  .call("append", c -> c.val(" "))
 			  .call("append", c -> c.get("l"))
 			  .call("append", c -> c.val(" "))
 			  .call("append", c -> c.get("s"))
 			  .call("toString");
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(cls.getMethod("test").invoke(null)).isEqualTo("1.5 5 str");
 		assertThat(collectOpcodes(bytes, "test"))
 		  .as("store-top must emit DSTORE/LSTORE/ASTORE")
 		  .contains(Opcodes.DSTORE, Opcodes.LSTORE, Opcodes.ASTORE);
 	}
 	
 	@DataProvider
 	Object[][] setConstRows(){
 		return new Object[][]{
 			{long.class,    99L,  99L},
 			{float.class,   1.5f, 1.5f},
 			{double.class,  2.25, 2.25},
 			{boolean.class, true, true},
 			{String.class,  "hi", "hi"},
 			{Class.class,   String.class, String.class},
 			{Class.class,   ClassName.dotted("java.lang.String"), String.class},
 		};
 	}
 	
 	// MT-14 — set(String, const) for the 7 remaining overloads: long/float/double/boolean/String/Class/ClassName
 	@Test(dataProvider = "setConstRows")
 	void mt14_setStringConst(Class<?> type, Object sample, Object expected) throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			var body = cd.function("rt").staticAcc().returns(type).body();
 			body.var(type, "x");
 			switch(sample){
 				case Long l      -> body.set("x", l);
 				case Float f     -> body.set("x", f);
 				case Double d    -> body.set("x", d);
 				case Boolean b   -> body.set("x", b);
 				case String s    -> body.set("x", s);
 				case Class<?> c  -> body.set("x", c);
 				case ClassName n -> body.set("x", n);
 				default          -> throw new IllegalStateException("Unexpected sample: " + sample);
 			}
 			body.get("x");
 		});
 		
 		assertThat(cls.getMethod("rt").invoke(null)).isEqualTo(expected);
 	}
 	
 	// MT-14 — set(FieldInfo, const) for all 8 overloads: PUTFIELD with a constant value
 	@Test
 	void mt14_setFieldConstOverloads() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			var fInt  = cd.field(int.class, "fInt");
 			var fLong = cd.field(long.class, "fLong");
 			var fFlt  = cd.field(float.class, "fFlt");
 			var fDbl  = cd.field(double.class, "fDbl");
 			var fBool = cd.field(boolean.class, "fBool");
 			var fStr  = cd.field(String.class, "fStr");
 			var fCls  = cd.field(Class.class, "fCls");
 			var fCn   = cd.field(Class.class, "fCn");
 			cd.function("fill").body()
 			  .get("this").set(fInt, 1)
 			  .get("this").set(fLong, 2L)
 			  .get("this").set(fFlt, 3.5f)
 			  .get("this").set(fDbl, 4.25)
 			  .get("this").set(fBool, true)
 			  .get("this").set(fStr, "hello")
 			  .get("this").set(fCls, Integer.class)
 			  .get("this").set(fCn, ClassName.dotted("java.lang.String"));
 		});
 		
 		var inst = cls.getDeclaredConstructor().newInstance();
 		cls.getMethod("fill").invoke(inst);
 		
 		assertThat(cls.getDeclaredField("fInt").get(inst)).isEqualTo(1);
 		assertThat(cls.getDeclaredField("fLong").get(inst)).isEqualTo(2L);
 		assertThat(cls.getDeclaredField("fFlt").get(inst)).isEqualTo(3.5f);
 		assertThat(cls.getDeclaredField("fDbl").get(inst)).isEqualTo(4.25);
 		assertThat(cls.getDeclaredField("fBool").get(inst)).isEqualTo(true);
 		assertThat(cls.getDeclaredField("fStr").get(inst)).isEqualTo("hello");
 		assertThat(cls.getDeclaredField("fCls").get(inst)).isEqualTo(Integer.class);
 		assertThat(cls.getDeclaredField("fCn").get(inst)).isEqualTo(String.class);
 	}
 	
 	// MT-14 — setThis(String): store the stack top into this.field (get this, swap, PUTFIELD)
 	@Test
 	void mt14_setThisString() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.field(int.class, "x");
 			cd.function("setX").arg(int.class, "v").body()
 			  .get("v").setThis("x");
 			cd.function("getX").returns(int.class).body()
 			  .getThis("x");
 		});
 		
 		var inst = cls.getDeclaredConstructor().newInstance();
 		cls.getMethod("setX", int.class).invoke(inst, 77);
 		assertThat(cls.getMethod("getX").invoke(inst)).isEqualTo(77);
 	}
 	
 	// MT-14 — set(Class,String)/get(Class,String): PUTSTATIC/GETSTATIC on a foreign class
 	@Test
 	void mt14_staticStoreLoadViaClass() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("roundTrip").staticAcc().returns(int.class)
 			  .body()
 			  .val(42).set(JorthCoverageStoreArithTests.class, "mutableCounter")
 			  .get(JorthCoverageStoreArithTests.class, "mutableCounter");
 			cd.function("maxInt").staticAcc().returns(int.class)
 			  .body()
 			  .get(Integer.class, "MAX_VALUE");
 		});
 		
 		assertThat(cls.getMethod("roundTrip").invoke(null)).isEqualTo(42);
 		assertThat(mutableCounter).isEqualTo(42);
 		assertThat(cls.getMethod("maxInt").invoke(null)).isEqualTo(Integer.MAX_VALUE);
 	}
 	
 	// MT-14 — set(ClassName,String)/get(ClassName,String): static round-trip on the generated class itself
 	@Test
 	void mt14_staticStoreLoadViaClassName() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.field(int.class, "counter").staticAcc();
 			cd.function("bump").staticAcc().returns(int.class)
 			  .body()
 			  .val(99).set(cd.name(), "counter")
 			  .get(cd.name(), "counter");
 		});
 		
 		assertThat(cls.getMethod("bump").invoke(null)).isEqualTo(99);
 		assertThat(cls.getField("counter").get(null)).isEqualTo(99);
 	}
 	
 	// ------------------------------------------------------------------ MT-15
 	
 	// MT-15 — long bitwise/shifts: LAND (const, int-const, two stack values), LSHL/LSHR/LUSHR with stack and local offsets
 	@Test
 	void mt15_longBitwiseShifts() throws Exception{
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			cd.function("andConst").staticAcc().returns(long.class)
 			  .body().val(0xABCDL).bitAnd(0x0F0FL);
 			cd.function("andConstInt").staticAcc().returns(long.class)
 			  .body().val(0xABCDL).bitAnd(0x0F0F);
 			cd.function("andStack").staticAcc().returns(long.class)
 			  .body().val(0x1234L).val(0x0FF0L).bitAnd();
 			cd.function("shlStack").staticAcc().returns(long.class)
 			  .body().val(1L).val(3).bitShiftLeft();
 			cd.function("shlLocal").staticAcc().returns(long.class)
 			  .body()
 			  .var(int.class, "shift")
 			  .set("shift", 3)
 			  .val(1L).get("shift").bitShiftLeft();
 			cd.function("shrStack").staticAcc().returns(long.class)
 			  .body().val(-16L).val(2).bitShiftRight(false);
 			cd.function("ushrStack").staticAcc().returns(long.class)
 			  .body().val(-16L).val(2).bitShiftRight(true);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(cls.getMethod("andConst").invoke(null)).as("0xABCD & 0x0F0F").isEqualTo(0x0B0DL);
 		assertThat(cls.getMethod("andConstInt").invoke(null)).as("0xABCD & 0x0F0F (int const)").isEqualTo(0x0B0DL);
 		assertThat(cls.getMethod("andStack").invoke(null)).as("two stack longs &").isEqualTo(0x0230L);
 		assertThat(cls.getMethod("shlStack").invoke(null)).as("1L << 3, stack offset").isEqualTo(8L);
 		assertThat(cls.getMethod("shlLocal").invoke(null)).as("1L << shift, local offset").isEqualTo(8L);
 		assertThat(cls.getMethod("shrStack").invoke(null)).as("-16L >> 2").isEqualTo(-4L);
 		assertThat(cls.getMethod("ushrStack").invoke(null)).as("-16L >>> 2").isEqualTo(0x3FFFFFFFFFFFFFFCL);
 		
 		assertThat(collectOpcodes(bytes, "andConst")).contains(Opcodes.LAND);
 		assertThat(collectOpcodes(bytes, "andConstInt")).contains(Opcodes.LAND);
 		assertThat(collectOpcodes(bytes, "andStack")).contains(Opcodes.LAND);
 		assertThat(collectOpcodes(bytes, "shlStack")).contains(Opcodes.LSHL);
 		assertThat(collectOpcodes(bytes, "shlLocal")).contains(Opcodes.LSHL);
 		assertThat(collectOpcodes(bytes, "shrStack")).contains(Opcodes.LSHR);
 		assertThat(collectOpcodes(bytes, "ushrStack")).contains(Opcodes.LUSHR);
 	}
 	
 	// MT-15 — add(int) on a long top: the Increment LONG case emits LADD
 	@Test
 	void mt15_longAdd() throws Exception{
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			cd.function("addLong").staticAcc().returns(long.class)
 			  .body().val(10L).add(5);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(cls.getMethod("addLong").invoke(null)).isEqualTo(15L);
 		assertThat(collectOpcodes(bytes, "addLong")).contains(Opcodes.LADD);
 	}
 	
 	// ------------------------------------------------------------------ MT-16
 	
 	// MT-16 — nested ifTrue inside ifTrue: all 4 input combinations select the right branch
 	@Test
 	void mt16_nestedIfTrue() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("test").staticAcc().arg(boolean.class, "a").arg(boolean.class, "b").returns(int.class)
 			  .body()
 			  .get("a")
 			  .ifTrue(c -> c.get("b")
			    .ifTrue(n -> n.val(1).returnOp())
			    .val(2).returnOp())
 			  .val(3).returnOp();
 		});
 		
 		var fn = cls.getMethod("test", boolean.class, boolean.class);
 		assertThat(fn.invoke(null, true, true)).as("a=T,b=T").isEqualTo(1);
 		assertThat(fn.invoke(null, true, false)).as("a=T,b=F").isEqualTo(2);
 		assertThat(fn.invoke(null, false, true)).as("a=F").isEqualTo(3);
 		assertThat(fn.invoke(null, false, false)).as("a=F").isEqualTo(3);
 	}
 	
 	// MT-16 — nested ifFalse inside ifTrue: the IFEQ path inside the IFNE path
 	@Test
 	void mt16_nestedIfFalse() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("test").staticAcc().arg(boolean.class, "a").arg(boolean.class, "b").returns(int.class)
 			  .body()
 			  .get("a")
 			  .ifTrue(c -> c.get("b")
			    .ifFalse(n -> n.val(1).returnOp())
			    .val(2).returnOp())
 			  .val(3).returnOp();
 		});
 		
 		var fn = cls.getMethod("test", boolean.class, boolean.class);
 		assertThat(fn.invoke(null, true, false)).as("a=T,b=F").isEqualTo(1);
 		assertThat(fn.invoke(null, true, true)).as("a=T,b=T").isEqualTo(2);
 		assertThat(fn.invoke(null, false, true)).as("a=F").isEqualTo(3);
 		assertThat(fn.invoke(null, false, false)).as("a=F").isEqualTo(3);
 	}
 	
 	// MT-16 — scope: the child's new stack values propagate into the parent (InlineBlock contract)
 	@Test
 	void mt16_scopeValuePropagation() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("test").staticAcc().returns(int.class)
 			  .body()
 			  .scope(c -> c.val(1))
 			  .add(2);
 		});
 		
 		assertThat(cls.getMethod("test").invoke(null)).isEqualTo(3);
 	}
 	
 	// MT-16 — nested scope: values propagate through two levels
 	@Test
 	void mt16_nestedScope() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("test").staticAcc().returns(int.class)
 			  .body()
 			  .scope(c -> c.scope(n -> n.val(5)).add(1))
 			  .add(10);
 		});
 		
 		assertThat(cls.getMethod("test").invoke(null)).isEqualTo(16);
 	}
 	
 	// ------------------------------------------------------------------ MT-17
 	
 	// MT-17 — varargs(): ACC_VARARGS emitted, spread and single-array invocation, isVarargs() model
 	@Test
 	void mt17_varargs() throws Exception{
 		var fnHolder = new FunctionDefinition[1];
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			fnHolder[0] = cd.function("first").staticAcc().arg(String[].class, "names").varargs().returns(String.class);
 			fnHolder[0].body().get("names").val(0).getArrayElement();
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(fnHolder[0].isVarargs()).as("model must track the varargs flag").isTrue();
 		
 		var fn = cls.getMethod("first", String[].class);
 		assertThat(fn.isVarArgs()).as("Method must be marked varargs").isTrue();
 		assertThat(methodAccess(bytes, "first") & Opcodes.ACC_VARARGS)
 		  .as("ACC_VARARGS must be emitted")
 		  .isNotZero();
 		
 		// at the reflection level a varargs call is a single-array call; ACC_VARARGS is what lets the
 		// compiler accept spread syntax, so the flag + isVarArgs() above are the spread pin
 		assertThat(fn.invoke(null, (Object)new String[]{"x", "y"})).as("array invocation").isEqualTo("x");
 	}
 	
 	// ------------------------------------------------------------------ MT-18
 	
 	// MT-18 — throwsException(Class...): the Exceptions attribute lists exactly the declared types, in order
 	@Test
 	void mt18_throwsExceptionAttribute() throws Exception{
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			cd.function("declared").staticAcc().returns(int.class)
 			  .throwsException(IllegalArgumentException.class, java.io.IOException.class)
 			  .body().val(0);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(collectExceptions(bytes, "declared"))
 		  .as("Exceptions attribute must list exactly the declared types")
 		  .containsExactly("java/lang/IllegalArgumentException", "java/io/IOException");
 		assertThat(cls.getMethod("declared").invoke(null)).isEqualTo(0);
 	}
 	
 	// MT-18 — throwsException(List) overload + getThrownExceptions() round-trip
 	@Test
 	void mt18_throwsExceptionListRoundTrip() throws Exception{
 		var fnHolder = new FunctionDefinition[1];
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			fnHolder[0] = cd.function("declared").staticAcc().returns(int.class)
 			  .throwsException(List.of(
 				  ClassName.dotted("java.lang.IllegalArgumentException"),
 				  ClassName.dotted("java.util.concurrent.TimeoutException")));
 			fnHolder[0].body().val(1);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(fnHolder[0].getThrownExceptions()).containsExactly(
 		  ClassName.dotted("java.lang.IllegalArgumentException"),
 		  ClassName.dotted("java.util.concurrent.TimeoutException"));
 		assertThat(collectExceptions(bytes, "declared")).containsExactly(
 		  "java/lang/IllegalArgumentException",
 		  "java/util/concurrent/TimeoutException");
 		assertThat(cls.getMethod("declared").invoke(null)).isEqualTo(1);
 	}
 	
 	// MT-18 — a method that actually throws a declared exception works end-to-end
 	@Test
 	void mt18_thrownExceptionAtRuntime() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.function("boom").staticAcc()
 			  .throwsException(IllegalArgumentException.class)
 			  .body()
 			  .newObj(IllegalArgumentException.class, c -> c.val("boom"))
 			  .throwOp();
 		});
 		
 		try{
 			cls.getMethod("boom").invoke(null);
 			assertThat(false).as("Method must have thrown").isTrue();
 		}catch(InvocationTargetException e){
 			assertThat(e.getCause()).isInstanceOf(IllegalArgumentException.class);
 			assertThat((IllegalArgumentException)e.getCause()).hasMessage("boom");
 		}
 	}
 	
 	// ------------------------------------------------------------------ MT-19
 	
 	// MT-19 — visibility flags on method/field + same-class private call: ACC_PRIVATE/ACC_PROTECTED emission
 	@Test
 	void mt19_visibilityAndPrivateSameClassCall() throws Exception{
 		var cls = generateAndLoadInstanceSimple(autoName(), cd -> {
 			cd.field(int.class, "secret").visibility(Visibility.PRIVATE);
 			
 			var secretFn = cd.function("secret").visibility(Visibility.PRIVATE).arg(int.class, "x").returns(int.class);
 			secretFn.body().get("x").add(1);
 			
 			cd.function("protectedFn").visibility(Visibility.PROTECTED).returns(int.class)
 			  .body().val(7);
 			
 			cd.function("callSecret").arg(int.class, "x").returns(int.class)
 			  .body().get("this").call(secretFn, c -> c.get("x"));
 		});
 		
 		assertThat(Modifier.isPrivate(cls.getDeclaredMethod("secret", int.class).getModifiers()))
 		  .as("secret method must be private").isTrue();
 		assertThat(Modifier.isProtected(cls.getDeclaredMethod("protectedFn").getModifiers()))
 		  .as("protectedFn must be protected").isTrue();
 		assertThat(Modifier.isPrivate(cls.getDeclaredField("secret").getModifiers()))
 		  .as("secret field must be private").isTrue();
 		assertThat(Modifier.isPublic(cls.getMethod("callSecret", int.class).getModifiers())).isTrue();
 		
 		var inst = cls.getDeclaredConstructor().newInstance();
 		assertThat(cls.getMethod("callSecret", int.class).invoke(inst, 41))
 		  .as("same-class private call must work").isEqualTo(42);
 	}
 	
 	// ------------------------------------------------------------------ MT-20
 	
 	// MT-20 — class staticAcc()/abstractAcc(): the model tracks the flags and the class still loads
 	// (the emitted class-level flags ignore them, B03 — pinned by the failing repro suite)
 	@Test
 	void mt20_classAccessFlags() throws Exception{
 		var staticCd = new ClassDefinition[1];
 		var staticBuilder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			staticCd[0] = cd;
 			cd.staticAcc();
 			cd.function("val").staticAcc().returns(int.class).body().val(42);
 		};
 		var staticCls = generateAndLoadInstanceSimple(autoName(), staticBuilder);
 		assertThat(staticCd[0].access().isStatic()).as("model must track staticAcc").isTrue();
 		assertThat(staticCls.getMethod("val").invoke(null)).isEqualTo(42);
 		
 		var abstractCd = new ClassDefinition[1];
 		var abstractBuilder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			abstractCd[0] = cd;
 			cd.abstractAcc();
 			cd.function("abs").returns(int.class);
 		};
 		var abstractCls = generateAndLoadInstanceSimple(autoName(), abstractBuilder);
 		assertThat(abstractCd[0].access().isAbstract()).as("model must track abstractAcc").isTrue();
 		assertThat(Modifier.isAbstract(abstractCls.getDeclaredMethod("abs").getModifiers()))
 		  .as("body-less method must be emitted abstract").isTrue();
 	}
 	
 	// MT-20 — function access(AccessSet)/abstractAcc: ACC_FINAL and ACC_ABSTRACT emitted on methods
 	@Test
 	void mt20_functionAccessFlags() throws Exception{
 		var fnHolder = new FunctionDefinition[1];
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			fnHolder[0] = cd.function("finalFn").access(AccessSet.FINAL).arg(int.class, "x").returns(int.class);
 			fnHolder[0].body().get("x");
 			cd.function("abstractFn").abstractAcc().returns(int.class);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(fnHolder[0].access()).isEqualTo(AccessSet.FINAL);
 		assertThat(fnHolder[0].isFinal()).isTrue();
 		assertThat(Modifier.isFinal(cls.getDeclaredMethod("finalFn", int.class).getModifiers()))
 		  .as("final method flag must be emitted").isTrue();
 		assertThat(methodAccess(bytes, "finalFn") & Opcodes.ACC_FINAL)
 		  .as("ACC_FINAL must be emitted").isNotZero();
 		
 		assertThat(Modifier.isAbstract(cls.getDeclaredMethod("abstractFn").getModifiers()))
 		  .as("abstract method flag must be emitted").isTrue();
 		assertThat(methodAccess(bytes, "abstractFn") & Opcodes.ACC_ABSTRACT)
 		  .as("ACC_ABSTRACT must be emitted").isNotZero();
 	}
 	
 	// MT-20 — field staticAcc/finalAcc/abstractAcc + isStatic()/type()/name()/owner() getters
 	@Test
 	void mt20_fieldAccessFlagsAndGetters() throws Exception{
 		var fHolder = new FieldDefinition[4];
 		var builder = (UnsafeConsumer<ClassDefinition, MalformedJorth>) cd -> {
 			fHolder[0] = cd.field(int.class, "st").staticAcc();
 			fHolder[1] = cd.field(int.class, "fin").finalAcc();
 			fHolder[2] = cd.field(int.class, "abs").abstractAcc();
 			fHolder[3] = cd.field(String.class, "txt").visibility(Visibility.PRIVATE);
 		};
 		var bytes = generateBytes(builder);
 		var cls   = generateAndLoadInstanceSimple(autoName(), builder);
 		
 		assertThat(Modifier.isStatic(cls.getDeclaredField("st").getModifiers()))
 		  .as("static field flag must be emitted").isTrue();
 		assertThat(Modifier.isFinal(cls.getDeclaredField("fin").getModifiers()))
 		  .as("final field flag must be emitted").isTrue();
 		assertThat(fieldAccess(bytes, "abs") & Opcodes.ACC_ABSTRACT)
 		  .as("ACC_ABSTRACT must be emitted on the field").isNotZero();
 		
 		assertThat(fHolder[0].isStatic()).isTrue();
 		assertThat(fHolder[0].access().isStatic()).isTrue();
 		assertThat(fHolder[1].access().isFinal()).isTrue();
 		assertThat(fHolder[2].access().isAbstract()).isTrue();
 		
 		assertThat(fHolder[3].name()).isEqualTo("txt");
 		assertThat(fHolder[3].type().asGeneric().raw().dotted()).isEqualTo("java.lang.String");
 		assertThat(fHolder[3].owner()).isEqualTo(ClassName.of(cls));
 	}
 	
 	// MT-20 — abstract+final flag combination is rejected with IllegalClassState (the module's unchecked error)
 	@Test
 	void mt20_finalAbstractIllegalState() throws Exception{
 		assertThatThrownBy(() -> new ClassDefinition(null).finalAcc().abstractAcc())
 		  .as("class: final then abstract must throw")
 		  .isInstanceOf(IllegalClassState.class)
 		  .hasMessageContaining("Can not be both abstract and final");
 		
 		var cd = new ClassDefinition(null);
 		cd.name(ClassName.dotted(autoName()));
 		assertThatThrownBy(() -> cd.function("fn").finalAcc())
 		  .as("function: starts abstract, so finalAcc must throw")
 		  .isInstanceOf(IllegalClassState.class)
 		  .hasMessageContaining("Can not be both abstract and final");
 		
 		var field = cd.field(int.class, "f");
 		assertThatThrownBy(() -> field.finalAcc().abstractAcc())
 		  .as("field: final then abstract must throw")
 		  .isInstanceOf(IllegalClassState.class)
 		  .hasMessageContaining("Can not be both abstract and final");
 	}
 }
