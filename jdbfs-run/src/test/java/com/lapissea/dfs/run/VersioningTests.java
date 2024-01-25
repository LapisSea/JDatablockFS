package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.LateInit;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
		}
	));
	
	static byte[] makeData(Class<?> type) throws IOException{
		var data = Cluster.emptyMem();
		data.roots().request("obj", type);
		return data.getSource().readAll();
	}
	
	private static final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	private static <T> Cluster versionedCl(Class<T> type, byte[] bb) throws IOException{
		IOInterface mem = LoggedMemoryUtils.newLoggedMemory(type.getName(), LOGGER);
		mem.write(0, true, bb);
		return new Cluster(mem);
	}
	
	static <T> T makeNGetRoot(Class<T> type) throws Exception{
		byte[] bb = makeCLDataRaw(type);
		
		var data = versionedCl(type, bb);
		return data.roots()
		           .require("obj", type);
	}
	
	private static byte[] makeCLDataRaw(Class<?> type) throws ReflectiveOperationException{
		return TestUtils.callWithClassLoader(SHADOW_CL, "make" + type.getSimpleName() + "Data");
	}
	
	@IOValue
	public static class A extends IOInstance.Managed<A>{
		int a = 69;
		int b = 420;
	}
	
	static byte[] makeAData() throws IOException{ return makeData(A.class); }
	
	@Test
	<T extends IOInstance<T>> void loadCorrectClass() throws Exception{
		var bb = makeCLDataRaw(A.class);
		var cl = new Cluster(MemoryData.viewOf(bb));
		
		//noinspection unchecked
		T obj = (T)cl.roots()
		             .require("obj", IOInstance.class);
		
		var type  = obj.getThisStruct();
		var names = type.getFields().map(IOField::getName).collectToSet();
		Assert.assertEquals(names, Set.of("a"));
		var aField = type.getFields().requireExact(int.class, "a");
		Assert.assertEquals(aField.get(null, obj), 1);
	}
	
}
