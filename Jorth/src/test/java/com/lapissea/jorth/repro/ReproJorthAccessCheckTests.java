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
 * JORTH-B08: no access checks on cross-class private members -> runtime IllegalAccessError
 * (should be rejected at build time).
 * <p>
 * What the code is supposed to do:
 * When the DSL emits an invoke of a method declared on ANOTHER class
 * (CodeBlock.call(ClassName, String) -> Insn.InvokeOp) or a read/write of a field
 * declared on another class (CodeBlock.get/set(Class, String) -> Insn.GetFieldOp /
 * Insn.PutFieldOp), it must validate ACCESS, not just types: a private member of a
 * different class is not accessible from the generated class, so the build must fail
 * fast with a checked MalformedJorth carrying a clear message - the same way javac
 * rejects the code at compile time.
 * <p>
 * What it actually does:
 * Insn.InvokeOp.simulate (Jorth/src/main/java/com/lapissea/jorth/Insn.java:731-759)
 * only checks argument/return TYPE compatibility (stack.pop() + instanceOf) and never
 * looks at FunctionInfo.visibility() or the caller's access at all.
 * Insn.GetFieldOp.simulate (Insn.java:647-660) and Insn.PutFieldOp.simulate
 * (Insn.java:626-645) only check the value type (popFieldOwner/instanceOf) and FieldInfo
 * does not even carry visibility information. So code that calls another class's
 * private method (here Target.reveal()) or reads its private field (here Target.secret)
 * PASSES codegen. ASM does not validate access flags and the JVM verifier does not
 * check them either (access is checked at RESOLUTION time, JVMS 5.4.4, which for
 * method/field references happens lazily at first execution of the instruction), so
 * getClassFile() succeeds and Class.forName(...) loads the class fine. Only when the
 * generated method is first executed does the JVM resolve the INVOKESTATIC/GETSTATIC
 * and throw IllegalAccessError - the error is deferred from build time to runtime.
 * <p>
 * Why the tests fail:
 * privateMethodCallShouldBeRejectedAtBuild and privateFieldAccessShouldBeRejectedAtBuild
 * assert the INTENDED behavior: the DSL rejects the cross-class private access at
 * build time with a MalformedJorth. Against the current code the build succeeds (no
 * exception at all), so assertThatCode(...).isInstanceOf(MalformedJorth.class) fails
 * with "Expecting code to raise a throwable" - the DSL silently accepts access that
 * javac would reject.
 * crossClassPrivateAccessThrowsAtRuntime PINS the actual buggy behavior (passes):
 * codegen and class loading succeed, and invoking the generated method throws
 * IllegalAccessError (wrapped in InvocationTargetException by reflection) - proving
 * codegen silently emits code that only fails at runtime.
 * publicMethodCallWorks (passes) is the positive control: calling a PUBLIC static
 * method of Target (different class) builds, loads and runs fine, showing the harness
 * is sound and the problem is specific to private access.
 */
public class ReproJorthAccessCheckTests{
	
	/**
	 * A real, already-compiled target class. The generated classes are placed in the
	 * "reproaccess" package, so every access from generated code to a member of this
	 * class is cross-class (and cross-package) - exactly the JORTH-B08 scenario.
	 * Target itself is public so the class is visible; only its private members are
	 * inaccessible.
	 */
	public static class Target{
		
		private static int secret = 7;
		
		private static int reveal(){
			return secret;
		}
		
		public static int publicValue(){
			return 42;
		}
	}
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthAccessCheckTests.class.getClassLoader()){
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
	void privateMethodCallShouldBeRejectedAtBuild(){
		// Intended behavior: Target.reveal() is PRIVATE and declared on a different
		// class, so the DSL must reject the cross-class private call at BUILD time
		// with a checked MalformedJorth (fail fast, clear message).
		// Current code: InvokeOp.simulate (Insn.java:731-759) checks only argument
		// and return types, never FunctionInfo.visibility(), so codegen succeeds and
		// no MalformedJorth is thrown -> this test FAILS.
		assertThatCode(() -> generateAndLoad("reproaccess.PrivateMethodCaller", cd -> {
			FunctionDefinition fn = cd.function("callReveal").staticAcc().returns(int.class);
			fn.body()
			  .call(Target.class, "reveal") // private static method, different class
			  .returnOp();
		}))
			.as("cross-class private method call must be rejected at build time with MalformedJorth")
			.isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void privateFieldAccessShouldBeRejectedAtBuild(){
		// Intended behavior: Target.secret is PRIVATE and declared on a different
		// class, so the DSL must reject the cross-class private field read at BUILD
		// time with a checked MalformedJorth.
		// Current code: GetFieldOp.simulate (Insn.java:647-660) checks only the value
		// type (popFieldOwner/instanceOf), never the field's access flags, so codegen
		// succeeds and no MalformedJorth is thrown -> this test FAILS.
		assertThatCode(() -> generateAndLoad("reproaccess.PrivateFieldReader", cd -> {
			FunctionDefinition fn = cd.function("readSecret").staticAcc().returns(int.class);
			fn.body()
			  .get(Target.class, "secret") // private static field, different class
			  .returnOp();
		}))
			.as("cross-class private field read must be rejected at build time with MalformedJorth")
			.isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void crossClassPrivateAccessThrowsAtRuntime() throws Exception{
		// PINS the actual buggy behavior: codegen silently accepts the cross-class
		// private call, getClassFile() succeeds, and Class.forName loads the class
		// (ASM and the JVM verifier do not check access flags). Only when the
		// generated method is first executed does the JVM resolve the INVOKESTATIC
		// to Target.reveal() and throw IllegalAccessError (JVMS 5.4.4).
		var cls = generateAndLoad("reproaccess.PrivateMethodCallerRuntime", cd -> {
			FunctionDefinition fn = cd.function("callReveal").staticAcc().returns(int.class);
			fn.body()
			  .call(Target.class, "reveal")
			  .returnOp();
		});
		// Reaching this point already proves codegen + class loading did NOT fail.
		var thrown = catchThrowable(() -> cls.getMethod("callReveal").invoke(null));
		assertThat(thrown)
			.as("invoking the generated method must fail at runtime; codegen accepted the illegal access")
			.isNotNull();
		Throwable root = thrown;
		while(root.getCause() != null && root.getCause() != root){
			root = root.getCause();
		}
		System.out.println("[JORTH-B08] runtime error from generated code: " + root);
		assertThat(root)
			.as("the JVM must reject the cross-class private call with IllegalAccessError "
			    + "(reflection wraps it in InvocationTargetException)")
			.isInstanceOf(IllegalAccessError.class);
	}
	
	@Test
	void publicMethodCallWorks() throws Exception{
		// Positive control: Target.publicValue() is PUBLIC, so the cross-class call
		// is legal. It must build, load and run correctly - proving the harness is
		// sound and JORTH-B08 is specific to private access.
		var cls = generateAndLoad("reproaccess.PublicMethodCaller", cd -> {
			FunctionDefinition fn = cd.function("callPublic").staticAcc().returns(int.class);
			fn.body()
			  .call(Target.class, "publicValue")
			  .returnOp();
		});
		assertThat(cls.getMethod("callPublic").invoke(null))
			.as("cross-class public static method call must build, load and return 42")
			.isEqualTo(42);
	}
}
