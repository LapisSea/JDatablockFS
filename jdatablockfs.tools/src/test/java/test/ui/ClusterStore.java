package test.ui;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.cluster.extensions.BlockMapCluster;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.tools.Common;
import com.lapissea.cfs.tools.DataLogger;
import com.lapissea.util.LateInit;
import com.lapissea.util.UtilL;
import com.lapissea.util.ZeroArrays;

import java.io.IOException;

public class ClusterStore{
	
	public static <K extends IOInstance> BlockMapCluster<K> start(Class<K> type) throws IOException{
		LateInit<DataLogger> display=Common.initAndLogger();
		MemoryData           mem    =Common.newLoggedMemory(display);
		
		BlockMapCluster<K> cluster=new BlockMapCluster<>(Cluster.build(b->b.withIO(mem)), type);
		
		new Thread(()->{
			UtilL.sleepUntil(display::isInited);
			try{
				mem.onWrite.accept(ZeroArrays.ZERO_LONG);
			}catch(IOException ignored){ }
		}).start();
		return cluster;
	}
}
