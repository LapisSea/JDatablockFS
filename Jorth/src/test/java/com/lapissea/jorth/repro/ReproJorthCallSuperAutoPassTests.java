package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JORTH-B19 (DOCS-ONLY): CodeBlock.callSuperAutoPass javadoc is wrong.
 *
 * The Javadoc (Jorth/src/main/java/com/lapissea/jorth/CodeBlock.java:449) says:
 *   "Calls super of the current function. The function has to be static. It will
 *    automatically gather all arguments and pass them."
 *
 * What the code actually does:
 *   The method body (CodeBlock.java:454-462) begins with `get("this")`
 *   (CodeBlock.java:456), i.e. it pushes the current INSTANCE and passes it as the
 *   receiver of the super call (see the `superCall=true` INVOKESPECIAL emitted at
 *   CodeBlock.java:461 / Insn.InvokeOp.visit line 777). So it requires an INSTANCE
 *   method, not a static one.
 *
 * Why a static method can NEVER work here:
 *   FunctionDefinition.initBody (FunctionDefinition.java:143-145) only defines the
 *   `this` local when the method is NOT static; a static method has no `this` local.
 *   CodeBlock.getLocal (CodeBlock.java:171-172) therefore throws
 *   MalformedJorth("Cannot use 'this' from a static function") the moment
 *   callSuperAutoPass() runs `get("this")` in a static context.
 *
 * There is NO behavioral bug: the code does exactly what it does. This is a pure
 * documentation defect. These tests PIN the actual behavior and document the
 * discrepancy (they PASS against the current code, so this is a behavior-pinning /
 * docs test, not a failing repro):
 *   - callSuperAutoPassPassesThisRequiresInstanceMethod: a generated SUB class
 *     extends a real SUPER class; an INSTANCE method calls the super method via
 *     callSuperAutoPass(), relying on the auto-passed `this`. It builds, loads,
 *     verifies and runs, proving the receiver is `this` (an instance).
 *   - callSuperAutoPassRejectsStaticContext: the contrasting case. A STATIC method
 *     calling callSuperAutoPass() fails at build time with
 *   MalformedJorth("Cannot use 'this' from a static function") - the direct proof
 *     that the "static" claim in the javadoc is wrong.
 */
public class ReproJorthCallSuperAutoPassTests{

	// A real (reflection-resolvable) SUPER class with an INSTANCE method. The generated
	// sub class will extend it and call this method via callSuperAutoPass(). The body is
	// a plain, distinctive computation so the result can only come from the super method.
	public static class B19SuperBase{
		public int doubleAndAdd(int x){
			return x * 2 + 1;
		}
	}

	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthCallSuperAutoPassTests.class.getClassLoader()){
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
	void callSuperAutoPassPassesThisRequiresInstanceMethod() throws Exception{
		// Step 1: build a SUB class that extends the real SUPER class (B19SuperBase).
		// Step 2: declare an INSTANCE method (no .staticAcc()) with the SAME name and
		//         signature as the super method, doubleAndAdd(int).
		// Step 3: its body is ONLY callSuperAutoPass() + returnOp(). callSuperAutoPass
		//         (CodeBlock.java:454) does `get("this")` (line 456) to push the receiver,
		//         then gathers the args (x), then emits an INVOKESPECIAL super call to
		//         B19SuperBase.doubleAndAdd(int) with that auto-passed `this`.
		// The returned value can therefore only be the SUPER method's result, proving the
		// receiver is `this` (an instance) - i.e. an instance method is required, which is
		// the opposite of the "static" javadoc at CodeBlock.java:449.
		var cls = generateAndLoad("reprocallsuper.B19Sub", cd -> {
			// The extension target. superType() (used by callSuperAutoPass's
			// resolveFunction, CodeBlock.java:460) resolves to B19SuperBase, so the
			// super method it calls is B19SuperBase#doubleAndAdd(int).
			cd.extendsType(B19SuperBase.class);
			// INSTANCE method (default access, NOT static) with the same signature as super.
			cd.function("doubleAndAdd")
			  .arg(int.class, "x")
			  .returns(int.class)
			  .body()
			  .callSuperAutoPass() // get("this"); get("x"); INVOKESPECIAL B19SuperBase#doubleAndAdd(int)
			  .returnOp();
			// (No explicit <init>: ensureConstructor (ClassDefinition.java:174) also builds
			//  the no-arg constructor via callSuperAutoPass(), which relies on `this` too.)
		});

		var inst = cls.getConstructor().newInstance();
		int x = 41;
		Object result = cls.getMethod("doubleAndAdd", int.class).invoke(inst, x);
		// The sub method body contains only callSuperAutoPass()+return, so the result is
		// B19SuperBase#doubleAndAdd(41) == 41*2+1 == 83. This only happens if `this` was
		// passed as the instance receiver, confirming the instance-method requirement.
		assertThat(result)
		    .as("callSuperAutoPass() must pass `this` (an instance) as the receiver of the super call; "
		      + "the javadoc at CodeBlock.java:449 wrongly says the method 'has to be static'")
		    .isEqualTo(x * 2 + 1);
	}

	@Test
	void callSuperAutoPassRejectsStaticContext() throws Exception{
		// The contrasting case that directly refutes the "static" javadoc. A STATIC method
		// has no `this` local (FunctionDefinition.java:143-145), so the very first
		// get("this") inside callSuperAutoPass (CodeBlock.java:456) hits getLocal
		// (CodeBlock.java:171-172) and throws MalformedJorth("Cannot use 'this' from a
		// static function") at build time. The class can never even be compiled, which is
		// the opposite of what a working "static" super-call helper would allow.
		assertThatThrownBy(() -> generateAndLoad("reprocallsuper.B19StaticFail", cd -> {
			// A STATIC method (note .staticAcc()) whose body calls callSuperAutoPass().
			FunctionDefinition fn = cd.function("doubleAndAdd")
			                           .staticAcc()
			                           .arg(int.class, "x")
			                           .returns(int.class);
			fn.body().callSuperAutoPass(); // throws MalformedJorth: "Cannot use 'this' from a static function"
			fn.body().val(0).returnOp();   // never reached
		}))
		.as("callSuperAutoPass() cannot be used in a static method because it passes `this`; "
		  + "this proves the javadoc claim 'The function has to be static' (CodeBlock.java:449) is wrong")
		.isInstanceOf(MalformedJorth.class)
		.hasMessageContaining("Cannot use 'this' from a static function");
	}
}
