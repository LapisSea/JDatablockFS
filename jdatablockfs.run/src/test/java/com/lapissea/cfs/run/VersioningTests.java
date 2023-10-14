package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDef;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.Jorth;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class VersioningTests{
	
	public static class AB extends IOInstance.Managed<AB>{
		@IOValue
		int a = 69;
		@IOValue
		int b = 420;
	}
	
	private static final ClassLoader SHADOW_CL = TestUtils.makeShadowClassLoader(Map.of(
		AB.class.getName(), name -> {
			return Jorth.generateClass(null, name, code -> {
				code.addImports(IOInstance.Managed.class, IOValue.class);
				code.write(
					"""
						extends #IOInstance.Managed<{0}>
						public class {0} start
							@ #IOValue
							public field a int
							
							public function <init> start
								super start end
								1 set this a
							end
						end
						""", name
				);
			});
		}
	));
	
	static byte[] makeABData() throws IOException{
		var data = Cluster.emptyMem();
		data.roots().request("obj", AB.class);
		return data.getSource().readAll();
	}
	
	@Test
	void ensureClassShadowing() throws Exception{
		byte[] bb = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeABData"));
		
		var data  = new Cluster(MemoryData.builder().withRaw(bb).build());
		var d     = Objects.requireNonNull(data.getTypeDb());
		var def   = d.getDefinitionFromClassName(AB.class.getName()).orElseThrow();
		var names = def.getFields().stream().map(TypeDef.FieldDef::getName).collect(Collectors.toSet());
		Assert.assertEquals(names, Set.of("a"));
	}
	
	@Test(dependsOnMethods = "ensureClassShadowing")
	void simpleNewField() throws Exception{
		byte[] bb = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeABData"));
		
		var data = new Cluster(MemoryData.builder().withRaw(bb).build());
		var val = data.roots()
		              .require("obj", AB.class);
		
		var expected = new AB();
		expected.a = 1;
		Assert.assertEquals(val, expected);
	}
	
}
