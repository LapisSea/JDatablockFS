package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.CodeBlock;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JORTH-B23: SUSPECTED / contract defect - FunctionDefinition.body() has NO
 * double-call guard.
 *
 * What the code is supposed to do (documented contract):
 *   The Jorth knowledge base (§2.2) and the coverage matrix documented that
 *   calling body() twice on the same function throws (a checked MalformedJorth
 *   on the second call). A caller relying on that contract expects a
 *   double body() call to be rejected.
 *
 * What it actually does:
 *   FunctionDefinition.body (FunctionDefinition.java:125-129) is an IDEMPOTENT
 *   accessor with no double-call guard:
 *       public CodeBlock body() throws MalformedJorth{
 *           var b = body;
 *           if(b == null) b = body = initBody();
 *           return b;
 *       }
 *   The first call lazily creates the body via initBody (FunctionDefinition.java:131-150:
 *   strips the abstract flag, registers the function via owner.finalize, creates
 *   the CodeBlock, defines the `this` local for non-static functions and the
 *   argument locals) and caches it in the `body` field. EVERY subsequent call
 *   returns the SAME cached CodeBlock instance and throws NOTHING. Because there
 *   is no guard, a caller that accidentally treats two body() results as
 *   separate, independent blocks actually builds on the ONE shared block - the
 *   two builds concatenate into a single block instead of failing.
 *   Severity: low - this is a docs/contract discrepancy + missing guard, NOT
 *   data corruption.
 *
 * Why the tests fail:
 *   bodyCalledTwiceShouldThrowMalformedJorth (THE BUG-TRIGGERING TEST) asserts the
 *   documented contract: the SECOND body() call must throw MalformedJorth. In
 *   the current code the second call simply returns the cached CodeBlock, so
 *   assertThatThrownBy fails with "Expecting code to raise a throwable but
 *   nothing was raised" - the failure is SPECIFICALLY the missing guard (no
 *   exception on the second call), not a build error (getClassFile is never
 *   called in this test).
 *   bodyCalledTwice_pinsIdempotentSameInstance PINS the actual behavior (passes
 *   against current code): two body() calls return the SAME CodeBlock instance
 *   and NO exception is thrown, proving the missing double-call guard
 *   (idempotent accessor).
 *   bodyCalledOnceAndBuildWorks (passes) is the control: a function whose body()
 *   is called ONCE, a valid instruction added, and the class built with
 *   getClassFile() - single-call usage is fine, isolating the issue to the
 *   unguarded second call.
 *
 * See also the DISABLED JorthCoverageNegativeTests.mt29_bodyCalledTwice
 * (Jorth/src/test/java/com/lapissea/jorth/JorthCoverageNegativeTests.java:82-91),
 * left @Test(enabled = false) precisely because the expected MalformedJorth is
 * NOT thrown: it calls fn.body() twice (second one building .val(42)) and
 * asserts the build throws MalformedJorth, which it never does.
 */
public class ReproJorthBodyGuardTests{

	@Test
	void bodyCalledTwiceShouldThrowMalformedJorth() throws MalformedJorth{
		// Step 1: plain function; the FIRST body() call is legitimate and works
		// (initBody creates and caches the CodeBlock, FunctionDefinition.java:126-128).
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("repobodyguard.Twice"));
		FunctionDefinition fn = cd.function("test").staticAcc().returns(int.class);
		fn.body(); // 1st call: fine - creates + caches the body

		// Step 2: the documented contract says the SECOND call must throw
		// MalformedJorth. Actual: body() is idempotent (FunctionDefinition.java:125-129)
		// - the second call returns the same cached CodeBlock and throws NOTHING,
		// so this test FAILS with "Expecting code to raise a throwable but nothing
		// was raised". No getClassFile() is involved, so the failure cannot be an
		// unrelated build error - it is exactly the missing double-call guard.
		assertThatThrownBy(fn::body)
		    .as("documented contract: a second body() call must throw MalformedJorth; "
		      + "actual behavior is idempotent - the SAME CodeBlock is returned and no "
		      + "exception is thrown (missing double-call guard, FunctionDefinition.java:125-129)")
		    .isInstanceOf(MalformedJorth.class);
	}

	@Test
	void bodyCalledTwice_pinsIdempotentSameInstance() throws MalformedJorth{
		// PINS the actual behavior (passes against current code): body() is an
		// idempotent accessor - the second call returns the very same CodeBlock
		// instance and NO exception is thrown. This proves the missing
		// double-call guard: building on both results would concatenate into one
		// block, not two independent blocks.
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("repobodyguard.SameInstance"));
		FunctionDefinition fn = cd.function("test").staticAcc().returns(int.class);
		CodeBlock first = fn.body();  // 1st call: creates + caches the body
		CodeBlock second = fn.body(); // 2nd call: no exception, returns the cached block
		assertThat(second)
		    .as("body() is idempotent (FunctionDefinition.java:125-129): the second call "
		      + "returns the cached CodeBlock instead of throwing MalformedJorth")
		    .isSameAs(first);
	}

	@Test
	void bodyCalledOnceAndBuildWorks() throws MalformedJorth{
		// Control (passes): a function whose body() is called ONCE, a valid
		// instruction added, and the class built - single-call usage of body()
		// is completely fine. This isolates the issue to the unguarded SECOND
		// call, proving the harness/DSL is sound.
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("repobodyguard.Once"));
		cd.function("test").staticAcc().returns(int.class)
		  .body() // single call: legitimate usage
		  .val(42)
		  .returnOp();
		byte[] bytes = cd.getClassFile();
		assertThat(bytes)
		    .as("a single body() call + valid instruction + getClassFile must build")
		    .isNotEmpty();
	}
}
