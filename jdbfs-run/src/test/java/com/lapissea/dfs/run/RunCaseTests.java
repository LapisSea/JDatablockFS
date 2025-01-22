package com.lapissea.dfs.run;

import com.lapissea.dfs.run.sparseimage.SparseImage;
import com.lapissea.util.ZeroArrays;
import org.testng.annotations.Test;

public class RunCaseTests{
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void sparseImage() throws Exception{
		SparseImage.main(ZeroArrays.ZERO_STRING);
	}
	
	@Test(dependsOnGroups = "rootProvider", ignoreMissingDependencies = true)
	void randomLists(){
		RandomLists.main(ZeroArrays.ZERO_STRING);
	}
	
}
