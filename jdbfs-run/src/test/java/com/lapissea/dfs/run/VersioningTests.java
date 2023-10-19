package com.lapissea.dfs.run;

import com.lapissea.dfs.chunk.Cluster;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.TypeDef;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class VersioningTests{
	
	@IOValue
	public static class A extends IOInstance.Managed<A>{
		int a = 69;
		int b = 420;
	}
	
	@IOValue
	public static class ABC extends IOInstance.Managed<ABC>{
		int a = 69;
		int c = 420;
	}
	
	
	@IOValue
	public static class IntVal extends IOInstance.Managed<IntVal>{
		List<Integer> val = new ArrayList<>();
	}
	
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
	
	static byte[] makeAData() throws IOException     { return makeData(A.class); }
	static byte[] makeABCData() throws IOException   { return makeData(ABC.class); }
	static byte[] makeIntValData() throws IOException{ return makeData(IntVal.class); }
	
	@Test
	void ensureClassShadowing() throws Exception{
		byte[] bb = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeAData"));
		
		var data  = new Cluster(MemoryData.builder().withRaw(bb).build());
		var d     = Objects.requireNonNull(data.getTypeDb());
		var def   = d.getDefinitionFromClassName(A.class.getName()).orElseThrow();
		var names = def.getFields().stream().map(TypeDef.FieldDef::getName).collect(Collectors.toSet());
		Assert.assertEquals(names, Set.of("a"));
	}
	
	@Test(dependsOnMethods = "ensureClassShadowing")
	void newField() throws Exception{
		byte[] bb   = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeABCData"));
		var    data = new Cluster(MemoryData.builder().withRaw(bb).build());
		
		var val = data.roots()
		              .require("obj", A.class);
		
		var expected = new A();
		expected.a = 1;
		Assert.assertEquals(val, expected);
	}
	
	@Test(dependsOnMethods = "ensureClassShadowing")
	void removedField() throws Exception{
		byte[] bb   = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeABCData"));
		var    data = new Cluster(MemoryData.builder().withRaw(bb).build());
		
		var val = data.roots()
		              .require("obj", ABC.class);
		
		var expected = new ABC();
		expected.a = 1;
		expected.c = 3;
		Assert.assertEquals(val, expected);
	}
	
	@Test(dependsOnMethods = "ensureClassShadowing")
	void changedTypeField() throws Exception{
		byte[] bb   = TestUtils.callWithClassLoader(SHADOW_CL, VersioningTests.class.getDeclaredMethod("makeIntValData"));
		var    data = new Cluster(MemoryData.builder().withRaw(bb).build());
		
		var val = data.roots()
		              .require("obj", IntVal.class);
		
		var expected = new IntVal();
		expected.val.add(1);
		Assert.assertEquals(val, expected);
	}
	
}
