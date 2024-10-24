package com.lapissea.dfs.run;

import com.lapissea.dfs.run.sparseimage.SparseImage;
import com.lapissea.dfs.run.world.RandomLists;
import com.lapissea.dfs.run.world.World;
import com.lapissea.util.ZeroArrays;
import org.testng.annotations.Test;

import java.io.IOException;

public class RunCaseTests{
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void sparseImage() throws Exception{
		SparseImage.main(ZeroArrays.ZERO_STRING);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void world() throws IOException{
		World.main(ZeroArrays.ZERO_STRING);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void randomLists(){
		RandomLists.main(ZeroArrays.ZERO_STRING);
	}
	
}
