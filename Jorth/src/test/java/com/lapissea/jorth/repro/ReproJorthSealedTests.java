package com.lapissea.jorth.repro;

import com.lapissea.jorth.ClassDefinition;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.jorth.lang.ClassName;
import com.lapissea.util.function.UnsafeBiConsumer;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

public class ReproJorthSealedTests{
	
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
					var    cd     = new ClassDefinition(this);
					generator.accept(dotted, cd);
					byte[] bytes = cd.getClassFile();
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
	public void generatedClassWithPermitsIsSealed() throws Exception{
		var classes = generateAndLoad(List.of("SealedClass", "child1", "child2"), this::defineHierarchy);
		var parent  = classes.get("SealedClass");
		assertThat(parent.isSealed()).isTrue();
		assertThat(parent.getPermittedSubclasses())
			.containsExactlyInAnyOrder(classes.get("child1"), classes.get("child2"));
		for(String name : List.of("child1", "child2")){
			var child = classes.get(name);
			assertThat(child.isSealed()).isFalse();
			assertThat(child.getConstructor().newInstance()).isInstanceOf(parent);
		}
	}
}
