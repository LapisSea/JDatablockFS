package com.lapissea.dfs.run;

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
import static org.assertj.core.api.Assertions.assertThat;

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
		assertThat(struct.needsBuilderObj()).as("Struct needs builder obj").isTrue();
		Log.info("Ok: {}#green", struct);
	}
	
	@Test(dependsOnMethods = "simpleInstanceStruct")
	public void generateProxy() throws IllegalAccessException{
		var res     = BuilderProxyCompiler.getProxy(Simple.class);
		var bStruct = Struct.of(res, STATE_DONE);
		var b       = bStruct.make();
		bStruct.getFields().requireExact(int.class, "val").set(null, b, 123);
		var simple = b.build();
		assertThat(simple).isEqualTo(new Simple(123));
	}
	
	@Test(dependsOnMethods = {"simpleInstanceStruct", "generateProxy"})
	public void simpleInstancePipe() throws IOException{
		var struct = Struct.of(Simple.class, STATE_DONE);
		var pipe   = StandardStructPipe.of(struct, STATE_DONE);
		
		var inst = new Simple(69);
		TestUtils.checkPipeInOutEquality(pipe, inst);
	}
	
}
