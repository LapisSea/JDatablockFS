package com.lapissea.jorth.repro;

import com.lapissea.jorth.AccessSet;
import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.lang.ClassName;
import org.testng.annotations.Test;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reproduction for BUG-36 (jorth-final).
 *
 * What the code is supposed to do:
 *   ClassDefinition.visitClass() must emit the access flags requested by the user via
 *   AccessSet (see ClassDefinition.access(AccessSet) / finalAcc()). A plain class created
 *   with the default AccessSet (non-final) and no permits must be emitted as a NON-final,
 *   NON-sealed class so it can be used as an extensible base in multi-class hierarchies.
 *
 * What it actually does:
 *   Jorth/src/main/java/com/lapissea/jorth/ClassDefinition.java:184 builds the CLASS flags as
 *       ACC_SUPER | (permits.isEmpty() ? ACC_FINAL : 0)
 *   i.e. the user's AccessSet is ignored entirely: whenever permits is empty ACC_FINAL is
 *   OR-ed in unconditionally, so EVERY generated class is either final (no permits) or
 *   pseudo-sealed (with permits, and even then without the ACC_SEALED flag - BUG-35).
 *   There is no way to emit a non-final, non-sealed class, and the emitted access flags
 *   silently differ from the AccessSet the user configured.
 *
 * Why the tests fail:
 *   - defaultClassIsEmittedFinal: a class is generated with a non-final AccessSet and no
 *     permits; the loaded class nevertheless reports Modifier.isFinal == true.
 *   - generatedBaseClassCannotBeExtended: a generated base class is extended by a generated
 *     child class; because the base was emitted final, the JVM rejects the child with a
 *     LinkageError ("attempts to extend final class"), making multi-class hierarchies
 *     (e.g. generated base + generated subclasses) impossible.
 */
public class ReproJorthFinalTests{

	@Test
	void defaultClassIsEmittedFinal() throws Exception{
		var cd = new ClassDefinition(null);
		cd.name(ClassName.dotted("reprofinal.Base"));
		// Explicit non-final AccessSet (same as the default): the user requests a
		// non-final, non-sealed class. finalAcc() is deliberately NOT called.
		cd.access(AccessSet.DEFAULT);
		assertThat(cd.access().isFinal())
			.as("precondition: the requested AccessSet is non-final")
			.isFalse();

		var cls = loadSingleClass("reprofinal.Base", cd.getClassFile());

		// BUG: ClassDefinition.java:184 unconditionally ORs ACC_FINAL into the class file
		// when permits is empty, ignoring the non-final AccessSet -> the loaded class is final.
		assertThat(Modifier.isFinal(cls.getModifiers()))
			.as("generated class must honor the requested non-final AccessSet, but visitClass (ClassDefinition.java:184) always emits ACC_FINAL when permits is empty")
			.isFalse();
	}

	@Test
	void generatedBaseClassCannotBeExtended() throws Exception{
		var names = Set.of("reprofinal.HierBase", "reprofinal.HierChild");
		var loader = newLoader(names, (name, cd) -> {
			// The base is created with the default (non-final) AccessSet and no permits:
			// it is meant to be an extensible base class.
			if(name.equals("reprofinal.HierChild")){
				cd.extendsType(ClassName.dotted("reprofinal.HierBase"));
			}
		});

		Class.forName("reprofinal.HierBase", true, loader); // base itself loads fine

		// BUG: the base was emitted final (ClassDefinition.java:184), so the JVM rejects
		// the child at link/verify time with a LinkageError ("attempts to extend final
		// class") -> a generated base class can never be extended.
		assertThatCode(() -> Class.forName("reprofinal.HierChild", true, loader))
			.as("a generated non-final base class must be extendable by a generated child")
			.doesNotThrowAnyException();
	}

	// --- private helpers (mirror TestUtils.generateAndLoadInstance, which is package-private) ---

	private static Class<?> loadSingleClass(String name, byte[] bytes) throws Exception{
		var loader = new ClassLoader(ReproJorthFinalTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String cn) throws ClassNotFoundException{
				if(cn.equals(name)){
					return defineClass(cn, bytes, 0, bytes.length);
				}
				return super.findClass(cn);
			}
		};
		return loader.loadClass(name);
	}

	private static ClassLoader newLoader(Set<String> names, BiConsumer<String, ClassDefinition> generator){
		return new ClassLoader(ReproJorthFinalTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				if(names.contains(name)){
					byte[] bytes;
					try{
						var cd = new ClassDefinition(this);
						cd.name(ClassName.dotted(name));
						generator.accept(name, cd);
						bytes = cd.getClassFile();
					}catch(Throwable e){
						throw new RuntimeException(e);
					}
					return defineClass(name, bytes, 0, bytes.length);
				}
				return super.findClass(name);
			}
		};
	}
}
