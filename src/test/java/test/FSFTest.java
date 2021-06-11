package test;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.util.LogUtil;

import java.io.IOException;

import static com.lapissea.util.LogUtil.Init.*;

public class FSFTest{
	public static void main(String[] args) throws IOException{
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		IOInterface data=MemoryData.build().build();
		Cluster.init(data);
		
		var cluster=new Cluster(data);
		LogUtil.println(cluster);
		LogUtil.println(data);
	}
}
