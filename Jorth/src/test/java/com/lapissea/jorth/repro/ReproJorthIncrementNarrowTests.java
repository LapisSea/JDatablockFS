package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.fail;

/**
 * JORTH-B21: Increment.simulate (.add(int)) does not update the simulated stack type
 * after byte/short/char + int -> the simulation keeps the NARROW type while the real
 * operand stack holds an int.
 *
 * What the code is supposed to do:
 *   CodeBlock.add(int) (CodeBlock.java:587-590) delegates to
 *   Insn.Increment.simulate (Jorth/src/main/java/com/lapissea/jorth/Insn.java:891-903).
 *   Per the JVM binary numeric promotion rules (JVMS 15.18.1: byte/short/char + int ->
 *   int, and IADD itself pops two int values and pushes an int), adding an int to a
 *   byte/short/char stack value produces an int, so the simulated operand stack must be
 *   updated: the narrow top must be replaced by GenericType.INT. Every later op
 *   (return, local store, ...) then sees the true type of the stack.
 *
 * What it actually does:
 *   Increment.simulate's int case (Insn.java:891-903) only PEEKS at the stack top:
 *   if it is int/byte/short/char it picks BaseType.INT purely for EMISSION (visit() at
 *   Insn.java:914-936 emits IVal + IADD), but never pops the narrow type and never
 *   pushes the resulting GenericType.INT back. The simulated stack top stays
 *   byte/short/char even though the real operand stack now holds an int.
 *
 * Two consequences:
 *   (A) FALSE REJECTION: a later op that expects an int sees the stale narrow type and
 *       throws MalformedJorth even though the emitted bytecode is valid. GenericType
 *       .instanceOf (lang/type/GenericType.java:196-202) performs NO widening for
 *       primitives: byte is not an instanceOf int. So ReturnOp.simulate
 *       (Insn.java:351-365) rejects a perfectly valid ILOAD + SIPUSH + IADD + IRETURN
 *       body with "Method returns int but byte is on stack".
 *   (B) SILENT TRUNCATION: storing the (actually-int) result into a byte local is
 *       ACCEPTED, because the stale byte type passes PutLocalVarOp.simulate's
 *       (Insn.java:664-670) byte.instanceOf(byte) check, and the store instruction is
 *       emitted from the LOCAL's type (fieldType.getBaseType() -> BSTORE) - silently
 *       truncating the real int (e.g. 100 + 200 = 300 -> 300 & 0xFF = 44). The DSL
 *       never allows implicit narrowing anywhere else (val(int).set(byteLocal) throws
 *       MalformedJorth), so this store must have been rejected too.
 *
 * This test demonstrates BOTH scenarios: (A) as the primary false-rejection tests and
 * (B) as the false-acceptance / truncation test.
 *
 * Why the tests fail (current code):
 *   bytePlusIntResultShouldBeUsableAsInt (A) and bytePlusIntIntoIntLocalShouldBuild
 *   (A-variant) assert the INTENDED behavior: the class builds and returns b + 10.
 *   Against the current code, .add(10) leaves the simulated top as byte, so the build
 *   fails with MalformedJorth("Method returns int but byte is on stack") /
 *   MalformedJorth("Tried to set local field of type int to byte") even though the
 *   emitted bytecode is valid.
 *   bytePlusIntStoredToByteLocalTruncates (B) asserts the INTENDED behavior: storing
 *   the int result into a byte local must be rejected at build time. Against the
 *   current code the build succeeds (stale byte passes the check) and the method
 *   returns the BSTORE-truncated 44 instead of 300.
 *   intPlusIntReturnsIntWorks and byteLocalWithoutAddReturnsByteWorks are positive
 *   controls (pass): int + int -> int return builds and runs, and a byte local is
 *   returned fine WITHOUT .add(int) - isolating the trigger to the stale type left by
 *   Increment.simulate.
 *
 * Note: CodeBlock.val has no byte/short/char overload (val((byte)5) compiles to
 * val(int), which pushes GenericType.INT), so the narrow simulated top is produced via
 * a byte argument/local and get(...), whose GetLocal.simulate (Insn.java:291-300)
 * pushes the local's byte type.
 */
public class ReproJorthIncrementNarrowTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthIncrementNarrowTests.class.getClassLoader()){
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
	void bytePlusIntResultShouldBeUsableAsInt() throws Exception{
		// (A) FALSE REJECTION - the failing (bug-triggering) test.
		// A byte argument loaded via ILOAD is an int on the REAL stack (JVMS 4.8.24:
		// ILOAD pushes an int). byte + 10 is an int per binary numeric promotion, so
		// returning it from an int method is perfectly valid bytecode:
		//   ILOAD 1; SIPUSH 10; IADD; IRETURN
		// Intended: the build succeeds and the method returns b + 10.
		// Current: Increment.simulate (Insn.java:891-903) leaves the simulated top as
		// byte after .add(10); ReturnOp.simulate (Insn.java:351-365) then checks
		// byte.instanceOf(int) -> false (no widening, GenericType.java:196-202) and
		// throws MalformedJorth("Method returns int but byte is on stack").
		var cls = generateAndLoad("test.CL_b21_bytePlusIntReturn", cd -> {
			FunctionDefinition fn = cd.function("bytePlusInt").staticAcc()
			                            .arg(byte.class, "b")
			                            .returns(int.class);
			fn.body()
			  .get("b")     // sim stack: [byte] (real stack: int via ILOAD)
			  .add(10)      // emits IADD -> real stack: int; sim stack STILL [byte] (B21)
			  .returnOp();  // sim: byte is not an instanceOf int -> MalformedJorth (stale)
		});
		assertThat(cls.getMethod("bytePlusInt", byte.class).invoke(null, (byte)5))
		    .as("byte 5 + int 10 must be usable as the int 15")
		    .isEqualTo(15);
	}
	
	@Test
	void bytePlusIntIntoIntLocalShouldBuild() throws Exception{
		// (A-variant) FALSE REJECTION: storing the int result into an INT local is
		// also rejected, because the stale simulated byte type fails
		// PutLocalVarOp.simulate's (Insn.java:664-670) byte.instanceOf(int) check.
		// Intended: builds and returns b + 10; current: MalformedJorth
		// "Tried to set local field of type int to byte".
		var cls = generateAndLoad("test.CL_b21_bytePlusIntToIntLocal", cd -> {
			FunctionDefinition fn = cd.function("bytePlusIntToIntLocal").staticAcc()
			                            .arg(byte.class, "b")
			                            .returns(int.class);
			fn.body()
			  .var(int.class, "r")
			  .get("b")     // sim stack: [byte]
			  .add(10)      // sim stack STILL [byte] (B21); real stack: int
			  .set("r")     // sim: byte is not an instanceOf int -> MalformedJorth (stale)
			  .get("r")
			  .returnOp();
		});
		assertThat(cls.getMethod("bytePlusIntToIntLocal", byte.class).invoke(null, (byte)5))
		    .as("byte 5 + int 10 stored into an int local must be 15")
		    .isEqualTo(15);
	}
	
	@Test
	void bytePlusIntStoredToByteLocalTruncates() throws Exception{
		// (B) SILENT TRUNCATION - the failing test.
		// b = 100, .add(200) computes the int 300 on the real stack. Storing it into a
		// byte local must be REJECTED at build time: the DSL never allows implicit
		// narrowing (val(int).set(byteLocal) throws MalformedJorth), because the
		// actual stack value is an int, not a byte.
		// Intended: MalformedJorth "Tried to set local field of type byte to int".
		// Current: Increment.simulate leaves the stale byte type, so
		// PutLocalVarOp.simulate (Insn.java:664-670) accepts byte.instanceOf(byte) and
		// emits BSTORE (from the local's type), silently truncating 300 to 300 & 0xFF = 44.
		Class<?> cls = null;
		try{
			cls = generateAndLoad("test.CL_b21_bytePlusIntStoreByte", cd -> {
				FunctionDefinition fn = cd.function("bytePlusIntStoreByte").staticAcc()
				                            .arg(byte.class, "b")
				                            .returns(byte.class);
				fn.body()
				  .var(byte.class, "tmp")
				  .get("b")     // sim stack: [byte]
				  .add(200)     // sim stack STILL [byte] (B21); real stack: int 300
				  .set("tmp")   // stale byte passes byte.instanceOf(byte) -> BSTORE truncates
				  .get("tmp")
				  .returnOp();
			});
		}catch(MalformedJorth e){
			// Correct behavior: the int result must not be implicitly narrowed to byte.
			assertThat(e)
			    .as("storing the int result (100 + 200 = 300) into a byte local must be rejected")
			    .hasMessageContaining("Tried to set local field of type byte to int");
			return;
		}
		// Buggy path: the build was accepted. Show the silent truncation.
		var result = cls.getMethod("bytePlusIntStoreByte", byte.class).invoke(null, (byte)100);
		fail("Expected MalformedJorth for int->byte local store (100 + 200 = 300 is an int, not a byte), "
		     + "but the stale simulated byte type let the store through and BSTORE silently truncated it to " + result);
	}
	
	@Test
	void intPlusIntReturnsIntWorks() throws Exception{
		// Control (passes): int + int -> int return. No narrow type involved, so the
		// stale-type bug cannot trigger; proves the harness (build, load, invoke) and
		// int return handling are sound - the bug needs a narrow initial type.
		var cls = generateAndLoad("test.CL_b21_intPlusInt", cd -> {
			FunctionDefinition fn = cd.function("intPlusInt").staticAcc()
			                            .arg(int.class, "x")
			                            .returns(int.class);
			fn.body()
			  .get("x")
			  .add(10)
			  .returnOp();
		});
		assertThat(cls.getMethod("intPlusInt", int.class).invoke(null, 7))
		    .as("int 7 + int 10 must be 17")
		    .isEqualTo(17);
	}
	
	@Test
	void byteLocalWithoutAddReturnsByteWorks() throws Exception{
		// Control (passes): a byte local is returned directly from a byte method -
		// no .add(int) involved, so the simulated stack never goes stale. Proves the
		// narrow-local + return harness is fine and isolates the trigger to
		// Increment.simulate's missing push-back of the resulting int.
		var cls = generateAndLoad("test.CL_b21_byteReturnNoAdd", cd -> {
			FunctionDefinition fn = cd.function("byteReturnNoAdd").staticAcc()
			                            .arg(byte.class, "b")
			                            .returns(byte.class);
			fn.body()
			  .get("b")
			  .returnOp();
		});
		assertThat(cls.getMethod("byteReturnNoAdd", byte.class).invoke(null, (byte)7))
		    .as("byte 7 must round-trip as 7")
		    .isEqualTo((byte)7);
	}
}
