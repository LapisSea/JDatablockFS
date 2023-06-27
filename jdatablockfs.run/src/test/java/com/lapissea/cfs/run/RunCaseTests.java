package com.lapissea.cfs.run;

import com.lapissea.cfs.run.sparseimage.SparseImage;
import com.lapissea.cfs.run.world.RandomLists;
import com.lapissea.cfs.run.world.World;
import com.lapissea.util.ZeroArrays;
import org.testng.annotations.Test;

import java.io.IOException;

public class RunCaseTests{
	
	@Test
	void sparseImage() throws Exception{
		SparseImage.main(ZeroArrays.ZERO_STRING);
	}
	
	@Test
	void world() throws IOException{
		World.main(ZeroArrays.ZERO_STRING);
	}
	
	@Test
	void randomLists() throws IOException{
		RandomLists.main(ZeroArrays.ZERO_STRING);
	}
	
}
