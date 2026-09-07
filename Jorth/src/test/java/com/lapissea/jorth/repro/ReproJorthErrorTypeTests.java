package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JORTH-B12: the documented checked exception MalformedJorth is caught and
 * rethrown as a plain (unchecked) RuntimeException at several sites, so a
 * caller doing `try { ... } catch (MalformedJorth e) { ... }` misses those
 * failures - they blow through as unchecked exceptions.
 *
 * What the code is supposed to do:
 *   MalformedJorth (Jorth/src/main/java/com/lapissea/jorth/exceptions/MalformedJorth.java,
 *   extends Exception) is Jorth's documented build-time failure type: the
 *   public API declares it everywhere, e.g. ClassDefinition.getClassFile
 *   (ClassDefinition.java:139) and every CodeBlock/FunctionDefinition DSL
 *   method. A caller catching MalformedJorth around a build must therefore be
 *   able to intercept EVERY build failure.
 *
 * What it actually does:
 *   Several internal sites catch MalformedJorth and rethrow it as an UNCHECKED
 *   RuntimeException, so those specific failures blow through a
 *   `catch (MalformedJorth)`:
 *     - CodeBlock.implicitReturn (CodeBlock.java:475-480):
 *           try { ... ReturnOp.simulate(...) ... }
 *           catch (MalformedJorth e) { throw new RuntimeException("Failed to return on " + fnOwner, e); }
 *       Reached from FunctionDefinition.visit (FunctionDefinition.java:187-193)
 *       whenever a non-terminating function body cannot be implicitly
 *       returned: Insn.ReturnOp.simulate (Insn.java:351-365) throws
 *       MalformedJorth, e.g. "Returning nothing (void) but there are values
 *       ... on the stack".
 *     - CodeBlock.resolveFunction (CodeBlock.java:497-499):
 *           catch (MalformedJorth ex) { throw new RuntimeException(ex); }
 *     - FunctionDefinition.visit (FunctionDefinition.java:190-192):
 *           catch (MalformedJorth e) { throw new RuntimeException("Failed to merge branch ...", e); }
 *     - ClassDefinition.ensureConstructor (ClassDefinition.java:175-177):
 *           catch (MalformedJorth e) { throw new RuntimeException(e); }
 *     - TypeSource.OfClassLoader.maybeByType0 (TypeSource.java:45-49):
 *           catch (MalformedJorth e) { throw new RuntimeException(e); }
 *
 * Why the tests fail:
 *   buildFailureShouldSurfaceAsMalformedJorth (THE BUG-TRIGGERING TEST)
 *   generates a class whose only real function is `void leaky(){ 1; }` - a
 *   VOID function whose body pushes an int and never returns. getClassFile()
 *   visits the function (FunctionDefinition.visit, FunctionDefinition.java:187);
 *   the body does not terminate, so CodeBlock.implicitReturn (CodeBlock.java:473)
 *   runs and ReturnOp.simulate (Insn.java:361) throws
 *   MalformedJorth("Returning nothing (void) but there are values [int] on the
 *   stack") - which CodeBlock.java:479 wraps into a plain
 *   RuntimeException("Failed to return on ..."). The test asserts the
 *   documented contract (the failure must surface as MalformedJorth), so it
 *   FAILS with "Expecting code to raise a throwable of type MalformedJorth
 *   but was actually RuntimeException" - i.e. a `catch (MalformedJorth)`
 *   would have missed this failure entirely.
 *   wrappingTest_pinsActualRuntimeException PINS the actual buggy behavior
 *   (passes against current code): the SAME trigger throws a RuntimeException
 *   whose cause is the original MalformedJorth, proving the failure is NOT
 *   directly catchable as MalformedJorth.
 *   directCallFailureThrowsMalformedJorth (passes) is the control: a
 *   CodeBlock.call to a NON-EXISTENT method propagates the MalformedJorth
 *   DIRECTLY - in CodeBlock.resolveFunction (CodeBlock.java:483-504) the
 *   by-name fallback stream is empty, so orElseThrow (CodeBlock.java:502)
 *   rethrows the ORIGINAL checked exception without wrapping (the wrap at
 *   CodeBlock.java:498 only guards the instanceOf check inside a non-empty
 *   fallback). This shows the documented contract type IS MalformedJorth and
 *   the wrapping sites are the inconsistent ones.
 */
public class ReproJorthErrorTypeTests{
	
	/**
	 * Builds a class whose only real function is
	 *     void leaky(){ 1; }
	 * i.e. a void function whose body pushes an int and never returns. The
	 * implicit return is impossible (a void method cannot return the int that
	 * is still on the stack), so getClassFile() must fail - the question this
	 * repro answers is with WHICH exception type it fails.
	 */
	private static ClassDefinition buildLeakyVoidClass() throws MalformedJorth{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("reproerrortype.LeakyVoid"));
		// void function (no .returns(...) -> returnType is null) whose body
		// leaves an int on the stack and has no returnOp()
		cd.function("leaky").body().val(1);
		return cd;
	}
	
	@Test
	void buildFailureShouldSurfaceAsMalformedJorth(){
		// Intended (documented) behavior: getClassFile() is declared
		// `throws MalformedJorth` (ClassDefinition.java:139), so the build
		// failure must surface as MalformedJorth and be catchable as such.
		// Current code: the failure goes through CodeBlock.implicitReturn
		// (CodeBlock.java:473-481), where the MalformedJorth thrown by
		// ReturnOp.simulate (Insn.java:361) is wrapped into a plain
		// RuntimeException (CodeBlock.java:479) -> this test FAILS: expected
		// MalformedJorth, actual exception type is RuntimeException.
		assertThatThrownBy(() -> buildLeakyVoidClass().getClassFile())
		    .as("the build failure must surface as the documented MalformedJorth; "
		      + "actual type is RuntimeException because CodeBlock.implicitReturn "
		      + "(CodeBlock.java:479) wraps the MalformedJorth in a plain RuntimeException")
		    .isInstanceOf(MalformedJorth.class);
	}
	
	@Test
	void wrappingTest_pinsActualRuntimeException(){
		// PINS the actual buggy behavior (passes against current code): the
		// SAME trigger throws a RuntimeException (NOT a MalformedJorth) whose
		// cause is the original MalformedJorth - i.e. a caller doing
		// `catch (MalformedJorth)` would miss this failure entirely.
		Throwable thrown = catchThrowable(() -> buildLeakyVoidClass().getClassFile());
		System.out.println("[JORTH-B12] actual exception: " + thrown
		                   + (thrown.getCause() != null? " (cause: " + thrown.getCause() + ")" : ""));
		assertThat(thrown)
		    .as("CodeBlock.implicitReturn (CodeBlock.java:479) rethrows the MalformedJorth "
		      + "as a plain RuntimeException, so it is not catchable as MalformedJorth")
		    .isInstanceOf(RuntimeException.class)
		    .hasMessageStartingWith("Failed to return on ")
		    .hasCauseInstanceOf(MalformedJorth.class);
		assertThat(thrown.getCause())
		    .as("the wrapped cause must be the original ReturnOp.simulate failure")
		    .hasMessageContaining("Returning nothing (void)");
	}
	
	@Test
	void directCallFailureThrowsMalformedJorth(){
		// Control (passes): a DIFFERENT, non-wrapped failure path. A
		// CodeBlock.call to a method that does not exist on String goes
		// through CodeBlock.resolveFunction (CodeBlock.java:483-504):
		// ClassInfo.getFunction throws MalformedJorth, the by-name fallback
		// stream is empty, and orElseThrow (CodeBlock.java:502) rethrows the
		// ORIGINAL MalformedJorth WITHOUT wrapping. This path honors the
		// documented contract, showing the contract type IS MalformedJorth
		// and the wrapping sites are the inconsistent ones.
		assertThatThrownBy(() -> {
			ClassDefinition cd = new ClassDefinition(null);
			cd.name(ClassName.dotted("reproerrortype.BadCall"));
			cd.function("badCall").staticAcc().returns(String.class)
			  .body()
			  .val("hello")
			  .call("noSuchMethod"); // no such method exists on String
			cd.getClassFile();
		})
		.as("a call to a non-existent method must throw MalformedJorth directly (non-wrapped path)")
		.isInstanceOf(MalformedJorth.class)
		.hasMessageContaining("noSuchMethod");
	}
}
