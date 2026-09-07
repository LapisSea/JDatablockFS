package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.FunctionDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeConsumer;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * JORTH-B01: primitive array creation emits ANEWARRAY instead of NEWARRAY, so the
 * generated class fails JVM verification.
 *
 * What the code is supposed to do:
 *   CodeBlock.newObj(int[].class) (and any other 1-D primitive array type) must emit the
 *   JVM NEWARRAY instruction with the primitive component as operand (NEWARRAY T_INT for
 *   int[], NEWARRAY T_CHAR for char[], ...), creating an instance that verifies and runs.
 *   Object arrays (e.g. String[]) must emit ANEWARRAY with the element class's internal
 *   name, which is the correct instruction for them.
 *
 * What it actually does:
 *   Insn.NewOp.visit (Jorth/src/main/java/com/lapissea/jorth/Insn.java:600-611) picks the
 *   opcode as
 *       case 1 -> type.getPrimitiveType().isPresent()? NEWARRAY : ANEWARRAY;
 *   but GenericType.getPrimitiveType (Jorth/src/main/java/com/lapissea/jorth/lang/type/GenericType.java:140-143)
 *   returns Optional.empty() whenever dims != 0 - which is exactly the array case - so for
 *   EVERY 1-D array type the ternary is always false and ANEWARRAY is always selected. For
 *   int[] the operand written (Insn.java:607) is the bare component name "int", which is not
 *   a class internal name, an invalid operand for ANEWARRAY (JVMS 6.5: ANEWARRAY's operand
 *   must name a class). ASM with COMPUTE_FRAMES treats "int" as an ordinary reference type
 *   while computing frames, so getClassFile() succeeds silently, but the emitted class file
 *   is invalid: the JVM verifier rejects the class at load/link time with a VerifyError
 *   (e.g. "Type '[Lint;' is not assignable to '[I'"). Object arrays such as String[] are
 *   unaffected because ANEWARRAY is the correct instruction for them.
 *
 * Why the tests fail:
 *   oneDimPrimitiveArrayIsCreated generates a class whose static method body is
 *   `val(4); newObj(int[].class); return`, loads it (Class.forName with initialize, which
 *   triggers verification) and invokes it, asserting the correct behavior: new int[4] is
 *   created and returned. Against the current code the load throws VerifyError (ANEWARRAY
 *   "int" operand / inconsistent stack map frame), so the assertion fails.
 *   oneDimPrimitiveCharArrayIsCreated repeats this with char[] to show the failure is not
 *   specific to the int component type. objectArrayControlWorks shows String[] loads and
 *   runs fine against the current code, isolating the failure to primitive component types
 *   and proving the harness itself is sound.
 */
public class ReproJorthPrimArrayTests{
	
	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthPrimArrayTests.class.getClassLoader()){
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
	void objectArrayControlWorks() throws Exception{
		// Control: String[] has a reference component, so ANEWARRAY "java/lang/String" is
		// the CORRECT instruction. This must pass even with the current code, proving the
		// harness is sound and the failures below are specific to primitive components.
		var cls = generateAndLoad("reproprimarray.StringArray", cd -> {
			FunctionDefinition fn = cd.function("makeStringArray").staticAcc().returns(String[].class);
			fn.body()
			  .val(3)              // push the array length
			  .newObj(String[].class) // new String[3] -> ANEWARRAY java/lang/String (valid)
			  .returnOp();
		});
		Object result = cls.getMethod("makeStringArray").invoke(null);
		assertThat(result)
		    .as("new String[3] in generated code must load, verify and run")
		    .isInstanceOf(String[].class);
		assertThat((String[]) result)
		    .as("the created array must have the requested length")
		    .hasSize(3);
	}
	
	@Test
	void oneDimPrimitiveArrayIsCreated() throws Exception{
		// BUG: the body is `val(4); newObj(int[].class); return`. NewOp.visit
		// (Insn.java:604) picks ANEWARRAY because GenericType.getPrimitiveType() is empty
		// for dims != 0 (GenericType.java:141), and emits ANEWARRAY with the operand "int"
		// (Insn.java:607) - an invalid operand. The correct emission is NEWARRAY T_INT.
		// The generated class file is therefore invalid and the JVM verifier rejects it.
		assertThatCode(() -> {
			var cls = generateAndLoad("reproprimarray.IntArray", cd -> {
				FunctionDefinition fn = cd.function("makeIntArray").staticAcc().returns(int[].class);
				fn.body()
				  .val(4)              // push the array length
				  .newObj(int[].class) // new int[4] -> emits ANEWARRAY "int" (bug: must be NEWARRAY T_INT)
				  .returnOp();
			});
			Object result = cls.getMethod("makeIntArray").invoke(null);
			assertThat(result)
			    .as("new int[4] in generated code must be created successfully")
			    .isInstanceOf(int[].class);
			assertThat((int[]) result)
			    .as("the created array must have the requested length")
			    .hasSize(4);
		})
		.as("generated code creating a 1-D primitive array must verify and run; "
		  + "NewOp.visit (Insn.java:604) emits ANEWARRAY for primitive arrays because "
		  + "GenericType.getPrimitiveType() (GenericType.java:141) is empty when dims != 0")
		.doesNotThrowAnyException();
	}
	
	@Test
	void oneDimPrimitiveCharArrayIsCreated() throws Exception{
		// Same bug with a different primitive component type (char), showing the failure
		// is not specific to the int component.
		assertThatCode(() -> {
			var cls = generateAndLoad("reproprimarray.CharArray", cd -> {
				FunctionDefinition fn = cd.function("makeCharArray").staticAcc().returns(char[].class);
				fn.body()
				  .val(2)              // push the array length
				  .newObj(char[].class) // new char[2] -> emits ANEWARRAY "char" (bug: must be NEWARRAY T_CHAR)
				  .returnOp();
			});
			Object result = cls.getMethod("makeCharArray").invoke(null);
			assertThat(result)
			    .as("new char[2] in generated code must be created successfully")
			    .isInstanceOf(char[].class);
			assertThat((char[]) result)
			    .as("the created array must have the requested length")
			    .hasSize(2);
		})
		.as("generated code creating a 1-D primitive char[] must verify and run; "
		  + "NewOp.visit (Insn.java:604) emits ANEWARRAY for primitive arrays")
		.doesNotThrowAnyException();
	}
}
