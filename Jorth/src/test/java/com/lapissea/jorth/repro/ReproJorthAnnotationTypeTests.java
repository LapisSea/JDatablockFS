package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.jorth.lang.type.ClassType;
import com.lapissea.util.function.UnsafeConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction for JORTH-B11 (jorth-annotation-type).
 *
 * What the code is supposed to do:
 *   A ClassDefinition whose type is ClassType.ANNOTATION (set via ClassDefinition.type(...),
 *   ClassDefinition.java:264) must be emitted as a REAL Java annotation type: the class file
 *   access_flags must include ACC_ANNOTATION (0x2000, JVMS 4.1) in addition to
 *   ACC_ABSTRACT|ACC_INTERFACE, exactly like a class compiled from an @interface declaration.
 *   Only then is the generated class a usable annotation type (Class.isAnnotation() is true,
 *   the java.lang.annotation machinery accepts it, and ASM readers see ACC_ANNOTATION).
 *
 * Environment note:
 *   This JDK's java.lang.reflect.Modifier has no isAnnotation(int) method (only the
 *   package-private ANNOTATION = 0x2000 constant survives), so the reflection oracle used
 *   here is Class.isAnnotation(); the raw class file bytes read through ASM ClassReader are
 *   the strongest oracle, and that is exactly where the defect lives (the access_flags
 *   passed to ClassWriter.visit in ClassDefinition.visitClass, ClassDefinition.java:185).
 *
 * What it actually does:
 *   ClassDefinition.visitClass (Jorth/src/main/java/com/lapissea/jorth/ClassDefinition.java:183-187)
 *   computes the class access flags as
 *       visibility.flag|switch(type){
 *           case CLASS -> ACC_SUPER|(permits.isEmpty()? ACC_FINAL : 0);
 *           case INTERFACE, ANNOTATION -> ACC_ABSTRACT|ACC_INTERFACE;   // <- ClassDefinition.java:185
 *           case ENUM -> ACC_SUPER|ACC_FINAL|ACC_ENUM;
 *       }
 *   i.e. a ClassType.ANNOTATION class is emitted with EXACTLY the same flags as a plain
 *   interface: ACC_ANNOTATION (0x2000) is never OR-ed in. The emitted class file is a plain
 *   (abstract) interface, not an annotation type: Class.isAnnotation() reports false on the
 *   loaded class and the class file access_flags lack 0x2000.
 *
 * Why the tests fail:
 *   annotationClassMustHaveAccAnnotationFlag generates a class with ClassType.ANNOTATION and
 *   one annotation member element (an abstract method "value():String"), then asserts the
 *   INTENDED behavior: the raw class file access_flags include ACC_ANNOTATION (0x2000) and
 *   Class.isAnnotation() is true on the loaded class. Against the current code
 *   visitClass emits ACC_ABSTRACT|ACC_INTERFACE without 0x2000, so the assertions fail.
 *   interfaceClassIsNotAnnotation and plainClassIsNotAnnotation are controls that PASS with
 *   the current code (a plain interface and a plain class correctly do NOT carry
 *   ACC_ANNOTATION), proving the harness is sound and the missing flag is specific to the
 *   ANNOTATION case of the switch at ClassDefinition.java:185.
 */
public class ReproJorthAnnotationTypeTests{

	/** loaded class plus the raw class file bytes it was defined from */
	private record Generated(Class<?> cls, byte[] bytes){}

	/** generate a single class with Jorth and load it (pattern of the other repro tests) */
	private static Generated generateAndLoad(
		String className,
		UnsafeConsumer<ClassDefinition, MalformedJorth> generator
	) throws Exception{
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted(className));
		generator.accept(cd);
		byte[] bytes = cd.getClassFile();
		var loader = new ClassLoader(ReproJorthAnnotationTypeTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String cn) throws ClassNotFoundException{
				if(cn.equals(className)){
					return defineClass(cn, bytes, 0, bytes.length);
				}
				return super.findClass(cn);
			}
		};
		return new Generated(loader.loadClass(className), bytes);
	}

	@Test
	void annotationClassMustHaveAccAnnotationFlag() throws Exception{
		// Request a class of type ANNOTATION through the DSL (ClassDefinition.type,
		// ClassDefinition.java:264). The bodyless function is the annotation member element
		// (emitted as an abstract method - the same mechanism as an interface method).
		var gen = generateAndLoad("reproannotationtype.Marker", cd -> {
			cd.type(ClassType.ANNOTATION);                    // <- the requested class type
			cd.function("value").returns(String.class);       // one member element -> valid annotation type
		});
		Class<?> cls = gen.cls();

		// sanity: the class file was emitted and loads as an interface (JVM accepts the flags)
		assertThat(cls.isInterface())
		           .as("generated class must at least load as an interface")
		           .isTrue();

		// BUG: visitClass (ClassDefinition.java:185) maps ANNOTATION to ACC_ABSTRACT|ACC_INTERFACE
		// and never ORs in ACC_ANNOTATION (0x2000), so the class file is a plain (abstract)
		// interface. Intended behavior: access_flags must include 0x2000. This assertion fails.
		int access = new ClassReader(gen.bytes()).getAccess();
		assertThat(access & Opcodes.ACC_ANNOTATION)
		           .as("class file access_flags (0x%04x) of a ClassType.ANNOTATION class must include "
			            + "ACC_ANNOTATION (0x2000); the INTERFACE/ANNOTATION case at "
			            + "ClassDefinition.java:185 only sets ACC_ABSTRACT|ACC_INTERFACE",
			           access)
		           .isNotZero();

		// Same defect visible through reflection: the loaded class is not an annotation type.
		assertThat(cls.isAnnotation())
		           .as("a class generated with ClassType.ANNOTATION must be a real annotation type "
			            + "(Class.isAnnotation), but visitClass (ClassDefinition.java:185) emits "
			            + "it as a plain abstract interface")
		           .isTrue();
	}

	@Test
	void interfaceClassIsNotAnnotation() throws Exception{
		// Control: ClassType.INTERFACE - a plain interface must NOT carry ACC_ANNOTATION.
		// This passes with the current code and shows the harness is sound: the switch at
		// ClassDefinition.java:185 handles INTERFACE and ANNOTATION identically, so the flag
		// is missing specifically because ANNOTATION is lumped in with INTERFACE.
		var gen = generateAndLoad("reproannotationtype.PlainInterface", cd -> {
			cd.type(ClassType.INTERFACE);
			cd.function("hello").returns(String.class);
		});
		Class<?> cls = gen.cls();

		assertThat(cls.isInterface())
		           .as("control: the generated class must be an interface")
		           .isTrue();
		int access = new ClassReader(gen.bytes()).getAccess();
		assertThat(access & Opcodes.ACC_ANNOTATION)
		           .as("a plain interface (ClassType.INTERFACE) must not carry ACC_ANNOTATION (0x2000)")
		           .isZero();
		assertThat(cls.isAnnotation())
		           .as("control: an interface is not an annotation type")
		           .isFalse();
	}

	@Test
	void plainClassIsNotAnnotation() throws Exception{
		// Control: a plain ClassType.CLASS (the default) must not carry ACC_ANNOTATION either.
		var gen = generateAndLoad("reproannotationtype.PlainClass", cd -> { });
		Class<?> cls = gen.cls();

		assertThat(cls.isInterface())
		           .as("control: the generated class must not be an interface")
		           .isFalse();
		int access = new ClassReader(gen.bytes()).getAccess();
		assertThat(access & Opcodes.ACC_ANNOTATION)
		           .as("a plain class (ClassType.CLASS) must not carry ACC_ANNOTATION (0x2000)")
		           .isZero();
		assertThat(cls.isAnnotation())
		           .as("control: a plain class is not an annotation type")
		           .isFalse();
	}
}
