package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

public class ReadyTest{
	public static void main(String[] args){
		try{
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
				mem.write(true, BakedData.getData());
				mem.onWrite.log(mem, LongStream.of());
				
				try{
					Cluster cluster;
					try{
						cluster=new Cluster(mem);
					}catch(IOException e){
						BakedData.genData();
						logger.get().getSession(sessionName).reset();
						mem.write(true, BakedData.getData());
						mem.onWrite.log(mem, LongStream.of());
						cluster=new Cluster(mem);
					}
					
					cluster.defragment();
					LogUtil.println(TextUtil.toNamedPrettyJson(cluster.gatherStatistics()));
				}finally{
					logger.block();
					mem.onWrite.log(mem, LongStream.of());
				}
			}finally{
				logger.get().destroy();
			}
			
			
		}catch(Throwable e){
			e.printStackTrace();
		}
	}
}
