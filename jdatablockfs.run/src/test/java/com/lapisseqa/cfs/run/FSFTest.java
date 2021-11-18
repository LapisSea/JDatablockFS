package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static com.lapissea.util.LogUtil.Init.USE_CALL_POS;
import static com.lapissea.util.LogUtil.Init.USE_TABULATED_HEADER;

class FSFTest{
	
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
				
				try{
					Cluster.init(mem);
					Cluster cluster=new Cluster(mem);
					
					intMapRun(cluster);
					LogUtil.println(TextUtil.toNamedPrettyJson(cluster.gatherStatistics()));
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
	
	private static void intMapRun(Cluster provider) throws IOException{
		
		boolean scramble=UtilL.sysPropertyByClass(FSFTest.class, "scramble").map(Boolean::parseBoolean).orElse(false);
		int     count   =UtilL.sysPropertyByClass(FSFTest.class, "count").map(Integer::parseInt).orElse(10);
		
		IOMap<Object, Object> map=provider.getTemp();
		
		int[] index=IntStream.range(0, count).toArray();
		
		if(scramble){
			Random r=new Random();
			r.setSeed(1);
			for(int i=0;i<index.length*2;i++){
				int from=r.nextInt(index.length);
				int to  =r.nextInt(index.length);
				
				int tmp=index[from];
				index[from]=index[to];
				index[to]=tmp;
			}
		}
		
		for(int i : index){
			map.put(i, "int("+i+")");
		}
	}
	
}
