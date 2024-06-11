package com.lapissea.dfs.run;

import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.objects.collections.PrefixTree;
import com.lapissea.dfs.run.checked.CheckSet;
import com.lapissea.dfs.tools.logging.DataLogger;
import com.lapissea.dfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.dfs.type.Struct;
import com.lapissea.util.LateInit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.Method;

public class PrefixTreeTest{
	
	@BeforeClass
	void before(){
		try{
			Struct.ofUnknown(PrefixTree.class, Struct.STATE_DONE);
		}catch(Throwable e){
			throw new RuntimeException("Failed to compile PrefixTree", e);
		}
	}
	
	private final LateInit<DataLogger, RuntimeException> LOGGER = LoggedMemoryUtils.createLoggerFromConfig();
	
	private Cluster testCluster;
	
	@BeforeMethod
	void setupCluster(Method method) throws IOException{
		var mem = LoggedMemoryUtils.newLoggedMemory(method.getName(), LOGGER);
		testCluster = Cluster.init(mem);
	}
	
	@AfterMethod
	void tearDownCluster(Method method) throws IOException{
		var s = testCluster.getSource();
		s.write(false, s.read(0, 1));
		var ses = LOGGER.get().getSession(method.getName());
		ses.finish();
	}
	
	private CheckSet<String> makeChecked() throws IOException{
		var data = testCluster.roots().request(0, PrefixTree.class);
		return new CheckSet<>(data);
	}
	
	@Test
	void simpleAdd() throws IOException{
		var checked = makeChecked();
		checked.add("AA-1");
		checked.add("AA-2");
		checked.add("AA-3");
		checked.add("AB-1");
		checked.add("");
		checked.add(null);
	}
	
	@Test(dependsOnMethods = "simpleAdd")
	void simpleRemove() throws IOException{
		var checked = makeChecked();
		checked.add("Dummy");
		checked.remove("Test");
		checked.add("Test");
		checked.remove("Test");
	}
	
	@Test(dependsOnMethods = {"simpleAdd", "simpleRemove"})
	void simpleContains() throws IOException{
		var checked = makeChecked();
		
		checked.add("Hello world");
		checked.add("Hey mum");
		checked.add("Hello there");
		
		checked.contains("Hi...?");
		checked.contains("Hello there");
		checked.remove("Hello there");
		checked.contains("Hello there");
	}
	
	@Test(dependsOnMethods = "simpleAdd")
	void simpleClear() throws IOException{
		var checked = makeChecked();
		
		checked.add("Hello world");
		checked.add("Hey mum");
		
		checked.clear();
	}
	
	
}
