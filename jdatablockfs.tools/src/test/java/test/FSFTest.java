package test;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.Common;
import com.lapissea.cfs.tools.DataLogger;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.*;

class FSFTest{
	
	public static void main(String[] args){
		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		
		try{
			LateInit<DataLogger> display=Common.initAndLogger();
			
//			if(DEBUG_VALIDATION) display.block();
			
			MemoryData<?> mem=Common.newLoggedMemory(display);
			Cluster.init(mem);
			Cluster cluster=new Cluster(mem);
			
			try{
				doTests(cluster);
			}finally{
				display.block();
				mem.onWrite.log(mem, LongStream.of());
				display.get().finish();
			}
			
		}catch(Throwable e1){
			e1.printStackTrace();
		}
		
	}
	
	private static void doTests(Cluster cluster) throws IOException{
		
		//cluster.pack();
		
	}
	
}
