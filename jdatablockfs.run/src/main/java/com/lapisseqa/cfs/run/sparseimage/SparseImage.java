package com.lapisseqa.cfs.run.sparseimage;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.Random;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class SparseImage{
	
	public static void main(String[] args) throws IOException{
		
		var config  =LoggedMemoryUtils.readConfig();
		var logFlags=0;
		if(Boolean.parseBoolean(config.getOrDefault("fancyPrint", "false").toString())){
			logFlags=USE_CALL_POS|USE_TABULATED_HEADER;
		}
		LogUtil.Init.attach(logFlags);
		
		LogUtil.println("config:", TextUtil.toNamedPrettyJson(config));
		
		String               sessionName="default";
		LateInit<DataLogger> logger     =LoggedMemoryUtils.createLoggerFromConfig();
		
		try{
			MemoryData<?> mem=LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			logger.ifInited(l->l.getSession(sessionName).reset());
			
			try{
				Cluster.init(mem);
				Cluster cluster=new Cluster(mem);
				
				
				run(cluster);
				
			}finally{
				logger.block();
				mem.onWrite.log(mem, LongStream.of());
			}
		}finally{
			logger.get().destroy();
		}
	}
	private static void run(Cluster cluster) throws IOException{
		
		var image=new Image();
		image.allocateNulls(cluster);
		cluster.getTemp().put(0, image);
		
		Random r=new Random(1);
		for(int i=0;i<100;i++){
			int x=(int)(Math.pow(r.nextFloat(), 3)*10);
			int y=(int)(Math.pow(r.nextFloat(), 3)*10);
			image.set(x, y, r.nextFloat(), r.nextFloat(), 1);
		}
		
		//cluster.defragment();
		
	}
	
}
