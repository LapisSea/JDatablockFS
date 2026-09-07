package com.lapissea.jorth.repro;

import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.GenericType;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JORTH-B14: GenericType.jvmString() drops a type variable's name when its bound is a
 * primitive -> emits the primitive descriptor instead of the "T"-form.
 *
 * What the code is supposed to do:
 *   Per JVMS 4.7.9.1, a generic signature writes a type variable as
 *       TypeVariable : "T" Identifier ";"
 *   so a type parameter named T must be emitted as "TT;" regardless of its bound. In
 *   Jorth's model a type variable is a GenericType whose `raw` component IS the bound
 *   type and whose `typeArgName` carries the variable's name (GenericType.java:23).
 *   GenericType.jvmString (GenericType.java:170-194) has a dedicated path for this:
 *   when the bound is not a primitive it appends 'T' + name (line 181) and ';'
 *   (line 190), producing exactly "TT;" for a variable named T.
 *
 * What it actually does:
 *   jvmString (GenericType.java:170) starts with
 *       var primitiveComp = BaseType.ofPrimitive(raw);      // line 171
 *   and when the bound is a primitive it falls into the else branch
 *       }else{
 *           sb.append(primitiveComp.jvmStr);               // lines 191-193
 *   which emits the plain primitive descriptor ("B" for byte, "I" for int, ...) and
 *   drops the typeArgName entirely, losing the type-variable identity. The twin
 *   jvmStringLen (GenericType.java:146-167) reports len = 1 (lines 163-165) instead of
 *   the 3 characters of "TT;". The same shape also makes the convenience method
 *   JType.jvmString(boolean) (JType.java:135-142) short-circuit via
 *   getPrimitiveType() (GenericType.java:140-143, which ignores typeArgName) and
 *   return the descriptor directly, so every public entry point shows the same "B".
 *
 * Why the tests fail:
 *   typeVariableWithPrimitiveBoundMustEmitTForm builds a type variable named T whose
 *   bound is byte (GenericType.of(byte.class).withTypeArgName(ClassName.dotted("T")))
 *   and asserts the INTENDED JVMS output: jvmString(sb, true) must yield "TT;" and
 *   jvmStringLen(true) must yield 3. The current code takes the primitive branch at
 *   GenericType.java:191-193 and emits "B" (length 1), so the assertion fails with
 *   "expected: "TT;" but was: "B"", demonstrating the lost type-variable identity.
 *   The controls (a reference-bound type variable and a plain byte) pass against the
 *   current code, proving the normal &lt;T&gt; "T"-form emission (lines 181, 190) and
 *   the primitive descriptor emission are each correct in their own domain, and
 *   isolating the failure to the primitive-bound type-variable case at
 *   GenericType.java:191-193 (and 163-165 for the length).
 *
 * Note: Java source cannot express "&lt;T extends byte&gt;" (type parameters may not be
 * bounded by primitives), so this shape of type only exists inside Jorth's own
 * GenericType model - which is exactly the model Jorth builds and serializes. A
 * white-box unit test of jvmString()/jvmStringLen() is therefore the cleanest
 * demonstration. Both methods are public (GenericType.java:146,170, overriding
 * JType.java:144-145), so they are called directly from this test's package - no
 * reflection is needed.
 */
public class ReproJorthJvmStringBoundTests{

	@Test
	void typeVariableWithPrimitiveBoundMustEmitTForm(){
		// A type variable named T whose bound is the PRIMITIVE byte. In Jorth's model
		// that is a GenericType with raw=byte (the bound) and typeArgName=T (the
		// variable's name). Not Java-source-expressible, but representable in the model.
		var typeVar = GenericType.of(byte.class).withTypeArgName(ClassName.dotted("T"));
		assertThat(typeVar.typeArgName())
		    .as("precondition: the type variable DOES carry the name T")
		    .isPresent();
		assertThat(typeVar.getPrimitiveType())
		    .as("precondition: the bound IS the primitive byte (what triggers the bug)")
		    .isPresent();
		// Intended output per JVMS 4.7.9.1 (TypeVariable : "T" Identifier ";"): "TT;".
		var sb = new StringBuilder();
		typeVar.jvmString(sb, true);
		// Bug: GenericType.java:171 computes primitiveComp=BYTE, and the else branch at
		// GenericType.java:191-193 appends the primitive descriptor "B", dropping both
		// the 'T' prefix (line 181) and the terminating ';' (line 190).
		assertThat(sb.toString())
		    .as("a type variable must be emitted in the JVMS T-form, not as its bound's descriptor")
		    .isEqualTo("TT;");
		// The twin defect in jvmStringLen: GenericType.java:163-165 counts the
		// descriptor (1 char) instead of the T-form (3 chars: T, T, ;).
		assertThat(typeVar.jvmStringLen(true))
		    .as("jvmStringLen must count the T-form 'TT;' (3 chars), not the descriptor (1)")
		    .isEqualTo(3);
	}

	@Test
	void typeVariableReferenceBoundEmitsTForm(){
		// Control: a type variable named T with a REFERENCE bound (Object) takes the
		// non-primitive path (GenericType.java:178-190), which already appends
		// 'T' + name (line 181) and ';' (line 190). This must PASS against the current
		// code, proving the normal <T> "T"-form emission is correct and the bug is
		// specific to primitive bounds.
		var typeVar = GenericType.of(Object.class).withTypeArgName(ClassName.dotted("T"));
		var sb = new StringBuilder();
		typeVar.jvmString(sb, true);
		assertThat(sb.toString())
		    .as("a reference-bound type variable already emits the JVMS T-form correctly")
		    .isEqualTo("TT;");
		assertThat(typeVar.jvmStringLen(true))
		    .as("the reference-bound T-form length is already correct")
		    .isEqualTo(3);
	}

	@Test
	void plainPrimitiveByteEmitsDescriptor(){
		// Control: a plain (non-type-variable) byte is NOT a type variable, so it must
		// be emitted as its primitive descriptor "B". This must PASS against the
		// current code, proving the primitive branch (GenericType.java:191-193) is
		// correct for true primitives - only type variables mis-route into it.
		var sb = new StringBuilder();
		GenericType.BYTE.jvmString(sb, true);
		assertThat(sb.toString())
		    .as("a plain primitive byte uses its descriptor")
		    .isEqualTo("B");
		assertThat(GenericType.BYTE.jvmStringLen(true))
		    .as("a plain primitive byte's descriptor is 1 char")
		    .isEqualTo(1);
	}
}
