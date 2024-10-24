package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.BuilderProxyCompiler;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.io.IOException;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class ReadOnlyTests{
	
	public static class Simple extends IOInstance.Managed<Simple>{
		@IOValue
		public final int val;
		
		public Simple(int val){
			this.val = val;
		}
	}
	
	@BeforeTest
	public void before(){
		Cluster.emptyMem();
	}
	
	@Test
	public void simpleInstanceStruct(){
		var struct = Struct.of(Simple.class, STATE_DONE);
		assertTrue(struct.needsBuilderObj(), "Struct needs builder obj");
		Log.info("Ok: {}#green", struct);
	}
	
	@Test(dependsOnMethods = "simpleInstanceStruct")
	public void generateProxy(){
		var res     = BuilderProxyCompiler.getProxy(Simple.class);
		var bStruct = Struct.of(res, STATE_DONE);
		var b       = bStruct.make();
		bStruct.getFields().requireExact(int.class, "val").set(null, b, 123);
		var simple = b.build();
		assertEquals(simple, new Simple(123));
	}
	
	@Test(dependsOnMethods = {"simpleInstanceStruct", "generateProxy"})
	public void simpleInstancePipe() throws IOException{
		var struct = Struct.of(Simple.class, STATE_DONE);
		var pipe   = StandardStructPipe.of(struct, STATE_DONE);
		
		var inst = new Simple(69);
		
		var mem = Cluster.emptyMem();
		var ch  = AllocateTicket.bytes(64).submit(mem);
		
		pipe.write(ch, inst);
		
		var read = pipe.readNew(ch, null);
		
		assertEquals(read, inst);
	}
	
}
