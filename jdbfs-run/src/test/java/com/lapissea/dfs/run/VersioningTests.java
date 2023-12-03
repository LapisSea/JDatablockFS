package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.versioning.Versioning;
import com.lapissea.dfs.core.versioning.VersioningOptions;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.TypeDef;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.LateInit;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class VersioningTests{
	
	public record Prop(String name, Class<?> type, Object val){ }
	
	private static void writeIOManagedClass(CodeStream code, String className, List<Prop> props) throws MalformedJorth{
		code.addImports(IOInstance.Managed.class, IOValue.class);
		code.write(
			"""
				extends #IOInstance.Managed<{0}>
				public class {0} start
					
					template-for #val in {1} start
						@ #IOValue
						public field #val.name #val.type
					end
					
					public function <init> start
						super start end
						template-for #val in {1} start
							#val.val set this #val.name
						end
					end
				end
				""",
			className, props
		);
	}
	
	private static final ClassLoader SHADOW_CL = TestUtils.makeShadowClassLoader(Map.of(
		A.class.getName(), name -> {
			return Jorth.generateClass(null, name, code -> {
				writeIOManagedClass(code, name, List.of(
					new Prop("a", int.class, 1)
				));
			});
		},
		ABC.class.getName(), name -> {
			return Jorth.generateClass(null, name, code -> {
				writeIOManagedClass(code, name, List.of(
					new Prop("a", int.class, 1),
					new Prop("b", int.class, 2),
					new Prop("c", int.class, 3)
				));
			});
		},
		IntVal.class.getName(), name -> {
			return Jorth.generateClass(null, name, code -> {
				writeIOManagedClass(code, name, List.of(
					new Prop("val", int.class, 1)
				));
			});
		}
	));
	
	static byte[] makeData(Class<?> type) throws IOException{
		var data = Cluster.emptyMem();
		data.roots().request("obj", type);
		return data.getSource().readAll();
	}
	
	private static final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	private static final Versioning VERSIONING =
		new Versioning(
			EnumSet.allOf(VersioningOptions.class),
			List.of()
		);
	
	static <T> T makeNGetRoot(Class<T> type) throws Exception{
		byte[] bb = TestUtils.callWithClassLoader(SHADOW_CL, "make" + type.getSimpleName() + "Data");
		
		IOInterface mem = LoggedMemoryUtils.newLoggedMemory(type.getName(), LOGGER);
		mem.write(0, true, bb);
		var data = new Cluster(mem, VERSIONING);
		
		return data.roots()
		           .require("obj", type);
	}
	
	@Test
	void ensureClassShadowing() throws Exception{
		byte[] bb   = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeAData"));
		var    data = new Cluster(MemoryData.builder().withRaw(bb).build(), VERSIONING);
		
		var d     = Objects.requireNonNull(data.getTypeDb());
		var def   = d.getDefinitionFromClassName(A.class.getName() + "€old").orElseThrow();
		var names = def.getFields().stream().map(TypeDef.FieldDef::getName).collect(Collectors.toSet());
		Assert.assertEquals(names, Set.of("a"));
	}
	
	
	@IOValue
	public static class A extends IOInstance.Managed<A>{
		int a = 69;
		int b = 420;
	}
	
	static byte[] makeAData() throws IOException{ return makeData(A.class); }
	@Test(dependsOnMethods = "ensureClassShadowing")
	void newField() throws Exception{
		var val = makeNGetRoot(A.class);
		
		var expected = new A();
		expected.a = 1;
		Assert.assertEquals(val, expected);
	}
	
	
	@IOValue
	public static class ABC extends IOInstance.Managed<ABC>{
		int a = 69;
		int c = 420;
	}
	static byte[] makeABCData() throws IOException{ return makeData(ABC.class); }
	@Test(dependsOnMethods = "ensureClassShadowing")
	void removedField() throws Exception{
		var val = makeNGetRoot(ABC.class);
		
		var expected = new ABC();
		expected.a = 1;
		expected.c = 3;
		Assert.assertEquals(val, expected);
	}
	
	@IOValue
	public static class IntVal extends IOInstance.Managed<IntVal>{
		List<Integer> val = new ArrayList<>();
	}
	static byte[] makeIntValData() throws IOException{ return makeData(IntVal.class); }
	@Test(dependsOnMethods = "ensureClassShadowing")
	void changedTypeField() throws Exception{
		var val = makeNGetRoot(IntVal.class);
		
		var expected = new IntVal();
		expected.val.add(1);
		Assert.assertEquals(val, expected);
	}
	
}
