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
 * JORTH-B02: instance-field finalAcc(CodeArg) initializer is spliced into <clinit>, so the
 * build fails (or throws an unchecked ClassCastException).
 *
 * What the code is supposed to do:
 *   FieldDefinition.finalAcc(CodeArg) on a NON-static (instance) field should initialize
 *   the instance field in the INSTANCE constructor path (where 'this' is available), so the
 *   generated class builds and the field reads back the initializer value. The STATIC
 *   variant staticFinal(CodeArg) splicing the initializer into <clinit> is correct for
 *   static fields, because PUTSTATIC needs exactly one stack element and <clinit> is where
 *   static initializers belong.
 *
 * What it actually does:
 *   FieldDefinition.init (Jorth/src/main/java/com/lapissea/jorth/FieldDefinition.java:60-65)
 *   is shared by BOTH finalAcc(CodeArg) and staticFinal(CodeArg) and always compiles the
 *   initializer into owner.staticInit().body() (i.e. <clinit>) and then calls
 *   body.setField(this). There is no isStatic() guard. For an INSTANCE field, the
 *   subsequent PutFieldOp.simulate (Jorth/src/main/java/com/lapissea/jorth/Insn.java:629)
 *   does stack.requireElements(field.isStatic()? 1 : 2) - requiring 2 elements (value +
 *   instance receiver 'this') - but <clinit> is static and has no 'this', so:
 *     - 1 value on the stack (e.g. b -> b.val(42)):
 *       MalformedJorth "Required at least 2 elements on the stack"
 *       (TypeStack.requireElements, Jorth/src/main/java/com/lapissea/jorth/lang/type/TypeStack.java:59);
 *     - 2 values happen to be on the stack (e.g. b -> b.val(1).val(42)):
 *       the lower value is popped as the "owner" and Insn.popFieldOwner
 *       (Jorth/src/main/java/com/lapissea/jorth/Insn.java:614-620) throws an UNCHECKED
 *       java.lang.ClassCastException ("<type> not compatible with <owner>") instead of a
 *       MalformedJorth - the wrong exception type for a build-time validation error.
 *
 * Why the tests fail:
 *   instanceFinalFieldInitializerFailsToBuild asserts the INTENDED correct behavior (the
 *   class builds and the instance field reads back 42); against the current code the
 *   generation throws MalformedJorth "Required at least 2 elements on the stack", which is
 *   quoted in the AssertionError so the failure reason is unambiguous.
 *   instanceFinalFieldTwoValuesClassCastException pins the second manifestation: the
 *   unchecked ClassCastException from popFieldOwner (Insn.java:618) that the bug produces
 *   when two values are on the stack.
 *   staticFinalFieldInitializerWorks is a positive control: the static path builds and
 *   reads back 42 with the current code, proving the harness is sound and the bug is
 *   specific to instance fields.
 */
public class ReproJorthFinalAccInitTests{

	private static Class<?> generateAndLoad(String className, UnsafeConsumer<ClassDefinition, MalformedJorth> generator) throws Exception{
		ClassDefinition cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthFinalAccInitTests.class.getClassLoader()){
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
	void instanceFinalFieldInitializerFailsToBuild() throws Exception{
		// Intended correct behavior: a class with an INSTANCE final field initialized via
		// finalAcc(CodeArg) builds, and the field reads back the initializer value.
		Class<?> cls;
		try{
			cls = generateAndLoad("reprofinalacc.InstanceInit", cd -> {
				// INSTANCE (non-static) final field with a CodeArg initializer.
				// finalAcc(init) delegates to FieldDefinition.init (FieldDefinition.java:60-65),
				// which splices the initializer into <clinit> and then calls body.setField(this)
				// on the NON-static field - no isStatic() guard.
				cd.field(int.class, "x").finalAcc(b -> b.val(42));
				// Getter to read the field back (instance method, so 'this' is available).
				FunctionDefinition fn = cd.function("getX").returns(int.class);
				fn.body().getThis("x").returnOp();
			});
		}catch(MalformedJorth e){
			// BUG JORTH-B02: quote the actual exception type + message so the failure reason
			// is unambiguous: <clinit> is static (no 'this'), so PutFieldOp.simulate's
			// requireElements(2) (Insn.java:629) fails with "Required at least 2 elements on
			// the stack" (TypeStack.java:59).
			throw new AssertionError(
			    "JORTH-B02: a class with an instance final field initialized via finalAcc(CodeArg) "
			    + "must build and the field must read back 42, but generation threw "
			    + e.getClass().getName() + ": " + e.getMessage(), e);
		}
		Object instance = cls.getDeclaredConstructor().newInstance();
		assertThat(cls.getMethod("getX").invoke(instance))
		    .as("the instance final field x must read back its initializer value 42")
		    .isEqualTo(42);
	}

	@Test
	void instanceFinalFieldTwoValuesClassCastException() throws Exception{
		// BUG JORTH-B02 (second manifestation): the initializer pushes TWO values, so
		// requireElements(2) (Insn.java:629) passes, but PutFieldOp.simulate then pops the
		// lower value (1) as the instance receiver and Insn.popFieldOwner (Insn.java:614-620)
		// throws an UNCHECKED java.lang.ClassCastException ("int not compatible with
		// reprofinalacc.TwoValueInit") instead of a MalformedJorth. This test pins that
		// wrong exception type.
		assertThatThrownBy(() -> generateAndLoad("reprofinalacc.TwoValueInit", cd -> {
			// Two values on the (static) <clinit> stack: the second is taken as the field
			// value, the first is mistaken for the 'this' receiver.
			cd.field(int.class, "x").finalAcc(b -> b.val(1).val(42));
			FunctionDefinition fn = cd.function("getX").returns(int.class);
			fn.body().getThis("x").returnOp();
		}))
		.as("JORTH-B02: splicing an instance-field initializer into <clinit> with two values on the stack "
		  + "produces the unchecked ClassCastException from popFieldOwner (Insn.java:618)")
		.isInstanceOf(ClassCastException.class)
		.hasMessageContaining("not compatible with");
	}

	@Test
	void staticFinalFieldInitializerWorks() throws Exception{
		// Positive control: the STATIC final field path (staticFinal(CodeArg)) is the
		// correct one - the initializer belongs in <clinit> and the store is emitted as
		// PUTSTATIC, which requires exactly one stack element. This must pass with the
		// current code, proving the harness is sound and the bug is specific to instance
		// fields.
		var cls = generateAndLoad("reprofinalacc.StaticInit", cd -> {
			cd.field(int.class, "x").staticFinal(b -> b.val(42));
			FunctionDefinition fn = cd.function("getX").staticAcc().returns(int.class);
			fn.body().get(cd.getField("x")).returnOp();
		});
		assertThat(cls.getMethod("getX").invoke(null))
		    .as("the static final field x must read back its initializer value 42")
		    .isEqualTo(42);
	}
}
