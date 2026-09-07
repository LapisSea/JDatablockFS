package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeBiConsumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-35 (jorth-sealed): generated "sealed" classes carry no sealed modifier in the class file.
 *
 * What the code is supposed to do:
 *   ClassDefinition.permits(...) declares the permitted subclasses of a generated class.
 *   Such a class must be emitted as a SEALED class file - i.e. with the ACC_SEALED
 *   modifier (0x2000, JVMS 4.1) in access_flags - so that the class file itself declares
 *   the sealed status (JVMS 5.4.4) and the sealed class is identifiable through reflection
 *   (Modifier.isSealed) as well as from the class file bytes.
 *
 * What it actually does:
 *   ClassDefinition.visitClass (Jorth/src/main/java/com/lapissea/jorth/ClassDefinition.java:184)
 *   computes the class access flags as
 *       visibility.flag | (ACC_SUPER | (permits.isEmpty() ? ACC_FINAL : 0))
 *   i.e. when permits is non-empty it only drops ACC_FINAL and NEVER adds ACC_SEALED.
 *   The PermittedSubclasses attribute is still emitted (ClassDefinition.java:202-204),
 *   so the class file carries the attribute but not the sealed modifier: the generated
 *   class is not a sealed class in its own class file, it merely lists permitted
 *   subclasses.
 *
 * Why the existing test does not catch this:
 *   JorthTests.sealedClass (Jorth/src/test/java/com/lapissea/jorth/JorthTests.java:781-796)
 *   only asserts on cls.getPermittedSubclasses(), which is populated from the
 *   PermittedSubclasses attribute ALONE and does not require the ACC_SEALED flag, so it
 *   passes even though the generated class file is not sealed.
 *
 * Environment note:
 *   In the JDK this runs on, java.lang.reflect.Modifier has no SEALED/isSealed members
 *   and Class.getModifiers() masks the sealed bit out of the reported modifiers (a class
 *   file with ACC_SEALED set still reports it stripped), and the bundled ASM Opcodes
 *   class has no ACC_SEALED constant either (so the 0x2000 constant is defined below).
 *   Reflection therefore cannot observe the sealed modifier at all in this environment;
 *   the raw class file bytes are the only reliable oracle, and that is exactly where the
 *   defect lives (the access_flags passed to ClassWriter.visit in ClassDefinition.java:184).
 *
 * Why this test fails:
 *   A class generated with cd.permits(...) is loaded and its class file bytes are
 *   inspected: the PermittedSubclasses attribute IS present (both permits listed, and
 *   getPermittedSubclasses() is non-empty - the "mismatch" the bug entry describes), but
 *   access_flags lacks ACC_SEALED (0x2000) because visitClass never sets it. The final
 *   assertion therefore fails.
 */
public class ReproJorthSealedTests{

	// ACC_SEALED (JVMS 4.1) is not available as a named constant in this environment
	// (java.lang.reflect.Modifier and org.objectweb.asm.Opcodes both lack it), so define it.
	private static final int ACC_SEALED = 0x2000;

	// classes generated below (all via Jorth):
	//   SealedClass    (default package): declares permits child1, child2  <- the "sealed" parent
	//   child1, child2 (default package): final, extend SealedClass        <- permitted subclasses

	private void defineHierarchy(String dottedName, ClassDefinition cd) throws MalformedJorth{
		cd.name(ClassName.dotted(dottedName));
		switch(dottedName){
			case "SealedClass" -> {
				cd.permits(ClassName.dotted("child1")).permits(ClassName.dotted("child2"));
			}
			case "child1", "child2" -> cd.finalAcc().extendsType(ClassName.dotted("SealedClass"));
			default -> throw new IllegalStateException("Unexpected class " + dottedName);
		}
	}

	/** raw bytes of each generated class, keyed by dotted name (captured at define time) */
	private final Map<String, byte[]> generatedBytes = new HashMap<>();

	/**
	 * Minimal multi-class generator/loader (same pattern as TestUtils.generateAndLoadInstanceMulti).
	 */
	private Map<String, Class<?>> generateAndLoad(
		List<String> dottedNames,
		UnsafeBiConsumer<String, ClassDefinition, MalformedJorth> generator
	) throws Exception{
		Set<String> slashedNames = dottedNames.stream()
		                                     .map(n -> n.replace('.', '/'))
		                                     .collect(Collectors.toSet());

		ClassLoader loader = new ClassLoader(ReproJorthSealedTests.class.getClassLoader()){
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException{
				// the caller's binary name may be dotted or slashed; normalize for lookup
				String slashed = name.replace('.', '/');
				if(!slashedNames.contains(slashed)){
					return super.findClass(name);
				}
				try{
					String dotted = name.replace('/', '.');
					var cd = new ClassDefinition(this);
					generator.accept(dotted, cd);
					byte[] bytes = cd.getClassFile();
					generatedBytes.put(dotted, bytes);
					return defineClass(dotted, bytes, 0, bytes.length);
				}catch(Throwable e){
					throw new RuntimeException(e);
				}
			}
		};

		Map<String, Class<?>> loaded = new LinkedHashMap<>();
		for(String n : dottedNames){
			loaded.put(n, Class.forName(n, true, loader));
		}
		return loaded;
	}

	@Test
	public void generatedClassWithPermitsMustBeSealed() throws Exception{
		var classes = generateAndLoad(List.of("SealedClass", "child1", "child2"), this::defineHierarchy);
		byte[] parentBytes = generatedBytes.get("SealedClass");

		// The PermittedSubclasses attribute IS emitted (ClassDefinition.java:202-204) and is
		// readable through reflection - this is the part the existing JorthTests.sealedClass
		// checks, and it passes even though the class file is not sealed (the "mismatch").
		Class<?> cls = classes.get("SealedClass");
		Class<?>[] permits = cls.getPermittedSubclasses();
		assertThat(permits).as("PermittedSubclasses attribute should be present").isNotNull();
		assertThat(permits).as("both declared permits should be listed").hasSize(2);

		// Inspect the generated class file bytes directly (this JDK's reflection API masks the
		// sealed bit out of Class.getModifiers, so the class file is the only reliable oracle).
		ClassReader cr = new ClassReader(parentBytes);
		List<String> permitsSeen = new ArrayList<>();
		cr.accept(new ClassVisitor(Opcodes.ASM9){
			@Override
			public void visitPermittedSubclass(String name){
				permitsSeen.add(name);
			}
		}, ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
		assertThat(permitsSeen)
		           .as("PermittedSubclasses attribute is emitted for both declared permits")
		           .containsExactly("child1", "child2");

		// BUG: visitClass (ClassDefinition.java:184) computes
		//     ACC_SUPER | (permits.isEmpty() ? ACC_FINAL : 0)
		// and never ORs in ACC_SEALED, so the class file carries the permits attribute but
		// no sealed modifier. This is the assertion that fails: access_flags lacks 0x2000.
		assertThat(cr.getAccess() & ACC_SEALED)
		           .as("class file access_flags must include ACC_SEALED (0x2000) when permits are "
		                + "declared; ClassDefinition.visitClass (ClassDefinition.java:184) never sets it")
		           .isNotZero();
	}
}
