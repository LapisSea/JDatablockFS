package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JORTH-B20: NullConstant / CodeBlock.nullVal(...) accepts a PRIMITIVE type with no
 * reference-type check -> simulates a primitive on the stack but emits ACONST_NULL (a
 * reference) -> the generated class fails JVM verification.
 *
 * What the code is supposed to do:
 *   CodeBlock.nullVal(type) (Jorth/src/main/java/com/lapissea/jorth/CodeBlock.java:631-636)
 *   pushes a null constant of the given type. null is only a valid value for reference
 *   types, so the DSL must reject nullVal(<primitive>) at BUILD time with a checked
 *   MalformedJorth (like javac rejects "int x = null;") - fail fast with a clear message.
 *
 * What it actually does:
 *   Insn.NullConstant.simulate (Jorth/src/main/java/com/lapissea/jorth/Insn.java:1034-1037)
 *   does `stack.push(type)` and its visit() emits ACONST_NULL (Insn.java:1040-1042) with
 *   NO check that the type is a reference type. So nullVal(int.class) SIMULATES an `int`
 *   on the stack: a following returnOp() in an int-returning method
 *   (Insn.ReturnOp.simulate, Insn.java:351-365) pops the simulated int, passes the
 *   instanceOf check (primitive INT vs INT, GenericType.java:196-202), and emits IRETURN
 *   from BaseType.INT.returnOp (Insn.java:368-370). The REAL bytecode is therefore
 *   `ACONST_NULL; IRETURN` in a ()I method: the verifier expects an int on the stack at
 *   IRETURN but ACONST_NULL pushed a reference (null) -> the class is rejected at load
 *   time with a VerifyError. ASM's frame computation does not catch the mismatch, so
 *   getClassFile() succeeds silently - the error is deferred from build time to load time.
 *
 * Why the tests fail/pass:
 *   nullValOnPrimitiveShouldBeRejectedAtBuild (FAILS) asserts the INTENDED behavior: the
 *   DSL rejects nullVal(int.class) at build time with a MalformedJorth. Against the
 *   current code the build succeeds - NullConstant.simulate pushes the int onto the
 *   simulated stack and no check fires - so no MalformedJorth is thrown; the generated
 *   class instead fails JVM verification at load time (a VerifyError, not a
 *   MalformedJorth).
 *   nullValOnPrimitiveFailsVerification (PASSES) pins the actual behavior: the SAME class
 *   builds fine, but Class.forName throws VerifyError (a reference ACONST_NULL where an
 *   int is expected), proving the simulated-primitive / emitted-reference mismatch.
 *   nullValOnReferenceTypeWorks (PASSES) is the positive control: nullVal(String.class)
 *   in a String-returning method emits ACONST_NULL + ARETURN, which verifies, so it
 *   builds, loads and returns null - proving the bug is specific to primitive types.
 */
public class ReproJorthNullPrimitiveTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthNullPrimitiveTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(name.equals(className)){
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
		return Class.forName(className, true, loader);
	}
	
	@Test
	void nullValOnPrimitiveShouldBeRejectedAtBuild(){
		// Intended behavior: null is not a valid value for a primitive type, so
		// nullVal(int.class) must be rejected at BUILD time with a checked MalformedJorth
		// (fail fast, clear message) - like javac rejecting "int x = null;".
		// Current code: NullConstant.simulate (Insn.java:1034-1037) only pushes the type
		// onto the simulated stack, with NO reference-type check, so the build SUCCEEDS
		// and no MalformedJorth is thrown; the generated class instead fails JVM
		// verification at load time with a VerifyError -> this test FAILS.
		assertThatCode(() -> generateAndLoad("repronullprim.IntNull", cd -> {
			FunctionDefinition fn = cd.function("nullInt").staticAcc().returns(int.class);
			fn.body()
			  .nullVal(int.class) // BUG: primitive type accepted; simulates an int on the stack
			  .returnOp();        // pops the simulated int -> emits IRETURN (real stack has a reference)
		}))
		.as("nullVal(int.class) must be rejected at build time with MalformedJorth; the build currently "
		  + "succeeds (NullConstant.simulate, Insn.java:1034-1037, pushes the primitive type with no "
		  + "reference-type check) and the class only fails later, at JVM verification")
		.isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void nullValOnPrimitiveFailsVerification() throws Exception{
		// PINS the actual buggy behavior: the build (DSL + getClassFile) SUCCEEDS because
		// NullConstant.simulate (Insn.java:1034-1037) accepts the primitive type and
		// ReturnOp.simulate (Insn.java:351-365) sees the simulated int on the stack, but
		// the emitted bytecode is `ACONST_NULL; IRETURN` in a ()I method - a reference
		// where an int is expected - so the JVM verifier rejects the class at load time.
		// Reaching the load (no MalformedJorth from codegen) already proves the build-OK
		// half; the VerifyError proves the emitted-reference half.
		Throwable thrown = catchThrowable(() -> generateAndLoad("repronullprim.IntNullVerify", cd -> {
			FunctionDefinition fn = cd.function("nullInt").staticAcc().returns(int.class);
			fn.body()
			  .nullVal(int.class) // simulates an int, emits ACONST_NULL (a reference)
			  .returnOp();        // emits IRETURN based on the simulated int
		}));
		System.out.println("[JORTH-B20] load/verification of generated class failed with: " + thrown);
		assertThat(thrown)
		    .as("codegen succeeds (no build-time rejection) and the class must fail JVM verification "
		      + "at load: ACONST_NULL (reference) where IRETURN expects an int")
		    .isInstanceOf(VerifyError.class);
	}
	
	@Test
	void nullValOnReferenceTypeWorks() throws Exception{
		// Positive control: null is a valid value for reference types. nullVal(String.class)
		// in a String-returning method emits ACONST_NULL + ARETURN, which verifies, so this
		// must build, load and return null - proving the bug is specific to primitive types
		// and the harness itself is sound.
		var cls = generateAndLoad("repronullprim.StringNull", cd -> {
			FunctionDefinition fn = cd.function("nullString").staticAcc().returns(String.class);
			fn.body()
			  .nullVal(String.class)
			  .returnOp();
		});
		assertThat(cls.getMethod("nullString").invoke(null))
		    .as("nullVal(String.class) in a String-returning method must build, load and return null")
		    .isNull();
	}
}
