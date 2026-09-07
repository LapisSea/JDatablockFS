package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JORTH-B22: several Insn.simulate paths throw an unchecked
 * IllegalArgumentException for unsupported operand types instead of the
 * documented MalformedJorth, so a caller doing
 * `try { ... } catch (MalformedJorth e) { ... }` misses those failures - they
 * blow through as unchecked exceptions.
 *
 * What the code is supposed to do:
 *   MalformedJorth (Jorth/src/main/java/com/lapissea/jorth/exceptions/MalformedJorth.java,
 *   `extends Exception` - a CHECKED exception) is Jorth's documented build-time
 *   failure type. Every CodeBlock DSL method declares it (e.g.
 *   CodeBlock.add(int), CodeBlock.java:587; CodeBlock.add(double),
 *   CodeBlock.java:591; CodeBlock.newObj, CodeBlock.java:283;
 *   CodeBlock.val, CodeBlock.java:193-207) and ClassDefinition.getClassFile
 *   (ClassDefinition.java:139) declares it too. A caller catching MalformedJorth
 *   around a build must therefore be able to intercept EVERY build failure,
 *   including "operand type not supported by this instruction".
 *
 * What it actually does:
 *   The Insn.simulate methods that reject an unsupported stack top throw the
 *   UNCHECKED IllegalArgumentException instead of the documented MalformedJorth:
 *     - Insn.Increment.simulate(stack, int) (Insn.java:891-903), else branch at
 *       Insn.java:900, reached when the stack top is NOT one of
 *       int/byte/short/char/long/float/double (e.g. a reference/object):
 *           throw new IllegalArgumentException("Cannot increment stack value of type: " + type + " by int");
 *     - Insn.Increment.simulate(stack, double) (Insn.java:904-912), else branch
 *       at Insn.java:909, reached when the stack top is NOT double (e.g. float):
 *           throw new IllegalArgumentException("Cannot increment stack value of type: " + type + " by double");
 *     - Insn.BitShiftLeft.simulate (Insn.java:941-957), else branch at
 *       Insn.java:953, reached when the VALUE (second-from-top) is not
 *       int/long:
 *           throw new IllegalArgumentException("Cannot bit shift stack value of type: " + value);
 *     - Insn.BitShiftRight.simulate (Insn.java:975-991), else branch at
 *       Insn.java:987, same operand check:
 *           throw new IllegalArgumentException("Cannot bit shift stack value of type: " + value);
 *   For contrast, the shift AMOUNT (the top of stack for the bit shifts) IS
 *   checked with the documented type at Insn.java:944 / Insn.java:978:
 *           throw new MalformedJorth("The bit shift amount must be an int but is: " + offset);
 *   so only the OPERAND-type paths are inconsistent.
 *   These simulates are invoked IMMEDIATELY at DSL-construction time
 *   (CodeBlock.add(int) -> Increment.simulate(localStack, val),
 *   CodeBlock.java:587-589; CodeBlock.add(double), CodeBlock.java:591-593), so
 *   the unchecked IllegalArgumentException propagates straight out of the
 *   .add(...) DSL call and past any `catch (MalformedJorth)`.
 *
 * Why the tests fail:
 *   addIntOnReferenceTopShouldThrowMalformedJorth (THE BUG-TRIGGERING TEST)
 *   builds a void function whose body is `new String(); .add(1)`:
 *   newObj(String.class) (CodeBlock.java:283) pushes a String reference on the
 *   type stack, then .add(1) (CodeBlock.java:587) calls
 *   Increment.simulate(stack, 1) whose top (String) matches none of the
 *   numeric cases, so the else at Insn.java:900 throws the UNCHECKED
 *   IllegalArgumentException("Cannot increment stack value of type: ... by int").
 *   The test asserts the documented contract (the failure must surface as
 *   MalformedJorth), so it FAILS with "Expecting code to raise a throwable of
 *   type MalformedJorth but was actually IllegalArgumentException" - i.e. a
 *   `catch (MalformedJorth)` would have missed this failure entirely.
 *   addIntOnReferenceTop_pinsActualIllegalArgument PINS the actual buggy
 *   behavior (passes against current code): the SAME trigger throws the
 *   unchecked IllegalArgumentException (NOT a MalformedJorth) from
 *   Insn.java:900, proving it is not catchable as MalformedJorth.
 *   addDoubleOnFloatTopShouldThrowMalformedJorth /
 *   addDoubleOnFloatTop_pinsActualIllegalArgument repeat the same pair for the
 *   second site: `val(1.5f); .add(1.5)` - the float top matches neither the
 *   double case (Insn.java:907) so the else at Insn.java:909 throws the
 *   unchecked IllegalArgumentException("... by double").
 *   addIntOnIntTopWorks (passes) is the control: `val(5); .add(1); return` on
 *   an INT top takes the working path (Insn.java:894-895, typ = BaseType.INT)
 *   and getClassFile() succeeds - proving the valid path is fine and the bug
 *   is specific to unsupported operand types.
 */
public class ReproJorthSimIaeTests{

	/**
	 * Builds a class whose only function is a void `leaky()` whose body is
	 *     new String();  // then .add(1)
	 * i.e. a void function whose body pushes a String REFERENCE onto the type
	 * stack (newObj(String.class), CodeBlock.java:283) and then calls .add(1)
	 * (the int increment, CodeBlock.java:587-589). Increment.simulate
	 * (Insn.java:891-903) peeks the stack top: String is not
	 * int/byte/short/char/long/float/double, so the else branch at Insn.java:900
	 * throws. The question this repro answers is with WHICH exception type it
	 * fails. Note the IllegalArgumentException is thrown at DSL-construction
	 * time (the .add(1) call), so getClassFile() below is never reached.
	 */
	private static byte[] buildAddIntOnReferenceTop() throws MalformedJorth{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("reprosimsiae.AddIntOnRef"));
		// void static function (no .returns(...)) whose body:
		cd.function("leaky").staticAcc()
		  .body()
		  .newObj(String.class) // push a String reference on the type stack
		  .add(1);              // Increment.simulate(stack, 1) -> IAE at Insn.java:900
		return cd.getClassFile(); // not reached: the IAE above blows through first
	}

	/**
	 * Same shape as buildAddIntOnReferenceTop but for the second site: a void
	 * `leaky()` whose body is
	 *     val(1.5f);  // then .add(1.5)
	 * val(1.5f) (CodeBlock.java:201) pushes a FLOAT on the type stack, then
	 * .add(1.5) (the DOUBLE increment overload, CodeBlock.java:591-593) calls
	 * Increment.simulate(stack, 1.5): the top (float) is not double
	 * (Insn.java:907), so the else branch at Insn.java:909 throws.
	 */
	private static byte[] buildAddDoubleOnFloatTop() throws MalformedJorth{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("reprosimsiae.AddDoubleOnFloat"));
		cd.function("leaky").staticAcc()
		  .body()
		  .val(1.5f) // push a float on the type stack
		  .add(1.5);  // Increment.simulate(stack, 1.5) -> IAE at Insn.java:909
		return cd.getClassFile(); // not reached: the IAE above blows through first
	}

	/**
	 * Control: the SAME .add(1) instruction on a VALID int top.
	 *     int inc(){ return 5 + 1; }
	 * val(5) pushes an int; Increment.simulate takes the working path
	 * (Insn.java:894-895, typ = BaseType.INT) and getClassFile() succeeds.
	 */
	private static byte[] buildAddIntOnIntTop() throws MalformedJorth{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("reprosimsiae.AddIntOnInt"));
		cd.function("inc").staticAcc().returns(int.class)
		  .body()
		  .val(5)
		  .add(1)      // int top -> Increment(INT), the supported path
		  .returnOp();
		return cd.getClassFile();
	}

	@Test
	void addIntOnReferenceTopShouldThrowMalformedJorth(){
		// Intended (documented) behavior: .add(int) is declared
		// `throws MalformedJorth` (CodeBlock.java:587) and getClassFile()
		// likewise (ClassDefinition.java:139), so an unsupported-operand build
		// failure must surface as MalformedJorth and be catchable as such.
		// Current code: the failure goes through Insn.Increment.simulate
		// (Insn.java:891-903) whose else branch (Insn.java:900) throws the
		// UNCHECKED IllegalArgumentException instead -> this test FAILS:
		// expected MalformedJorth, actual exception type is IllegalArgumentException.
		assertThatThrownBy(() -> buildAddIntOnReferenceTop())
		    .as("adding an int to a reference stack top (new String(); .add(1)) must fail "
		      + "with the documented MalformedJorth; actual type is IllegalArgumentException "
		      + "thrown at Insn.java:900")
		    .isInstanceOf(MalformedJorth.class);
	}

	@Test
	void addIntOnReferenceTop_pinsActualIllegalArgument(){
		// PINS the actual buggy behavior (passes against current code): the
		// SAME trigger throws the UNCHECKED IllegalArgumentException from
		// Insn.java:900 - NOT the documented MalformedJorth - so a caller doing
		// `catch (MalformedJorth)` misses this failure entirely.
		Throwable thrown = catchThrowable(() -> buildAddIntOnReferenceTop());
		System.out.println("[JORTH-B22] actual exception: " + thrown);
		assertThat(thrown)
		    .as("Insn.Increment.simulate (Insn.java:900) throws an unchecked "
		      + "IllegalArgumentException for a non-numeric stack top instead of MalformedJorth")
		    .isInstanceOf(IllegalArgumentException.class);
		assertThat(thrown)
		    .as("the unchecked IllegalArgumentException is NOT catchable as the documented MalformedJorth")
		    .isNotInstanceOf(MalformedJorth.class);
		assertThat(thrown)
		    .as("the message must be the Insn.java:900 one, proving the exact IAE site")
		    .hasMessageStartingWith("Cannot increment stack value of type: ")
		    .hasMessageContaining("by int");
	}

	@Test
	void addDoubleOnFloatTopShouldThrowMalformedJorth(){
		// Same contract assertion for the second site: .add(double) on a FLOAT
		// top. Insn.Increment.simulate(stack, double) (Insn.java:904-912) only
		// accepts a double top (Insn.java:907); the float top hits the else at
		// Insn.java:909, which throws the UNCHECKED IllegalArgumentException ->
		// this test FAILS: expected MalformedJorth, actual type is
		// IllegalArgumentException.
		assertThatThrownBy(() -> buildAddDoubleOnFloatTop())
		    .as("adding a double to a float stack top (val(1.5f); .add(1.5)) must fail "
		      + "with the documented MalformedJorth; actual type is IllegalArgumentException "
		      + "thrown at Insn.java:909")
		    .isInstanceOf(MalformedJorth.class);
	}

	@Test
	void addDoubleOnFloatTop_pinsActualIllegalArgument(){
		// PINS the actual buggy behavior at the second site (passes against
		// current code): the SAME trigger throws the UNCHECKED
		// IllegalArgumentException from Insn.java:909 - NOT the documented
		// MalformedJorth.
		Throwable thrown = catchThrowable(() -> buildAddDoubleOnFloatTop());
		System.out.println("[JORTH-B22] actual exception: " + thrown);
		assertThat(thrown)
		    .as("Insn.Increment.simulate (Insn.java:909) throws an unchecked "
		      + "IllegalArgumentException for a non-double stack top instead of MalformedJorth")
		    .isInstanceOf(IllegalArgumentException.class);
		assertThat(thrown)
		    .as("the unchecked IllegalArgumentException is NOT catchable as the documented MalformedJorth")
		    .isNotInstanceOf(MalformedJorth.class);
		assertThat(thrown)
		    .as("the message must be the Insn.java:909 one, proving the exact IAE site")
		    .hasMessageStartingWith("Cannot increment stack value of type: ")
		    .hasMessageContaining("by double");
	}

	@Test
	void addIntOnIntTopWorks() throws MalformedJorth{
		// Control (passes): .add(1) on a VALID int top takes the working path
		// (Insn.java:894-895, typ = BaseType.INT), so the whole build - DSL
		// plus getClassFile() - succeeds. This proves the harness is sound and
		// isolates the bug to unsupported operand types (the IAE sites at
		// Insn.java:900/:909/:953/:987), not to .add(int) in general.
		byte[] bytes = buildAddIntOnIntTop();
		assertThat(bytes)
		    .as(".add(1) on an int top must build a valid class file")
		    .isNotEmpty();
	}
}
