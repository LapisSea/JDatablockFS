package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.Random;
import java.util.stream.LongStream;

class FSFTest{
	
	public static void main(String[] args){
//		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		LogUtil.Init.attach(0);
		
		
		try{
			String               sessionName="default";
			LateInit<DataLogger> logger     =LoggedMemoryUtils.createLoggerFromConfig();
			try{
					MemoryData<?> mem=LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
					logger.ifInited(l->l.getSession(sessionName).reset());
					
					try{
						Cluster.init(mem);
						Cluster cluster=new Cluster(mem);
						
						doTests(cluster);
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
	
	private static void doTests(Cluster provider) throws IOException{

//		var chunk=AllocateTicket.bytes(32).submit(provider);
//
//		var ref=chunk.getPtr().makeReference(0);
//		var typ=TypeDefinition.of(ContiguousIOList.class, Dummy.class);
//
//		ContiguousIOList<Dummy> list=new ContiguousIOList<>(provider, ref, typ);
//
//		list.add(new Dummy(69));
//		list.add(new Dummy(420));
//
//		var meta=provider.getGenericTypes();
//		meta.add(new StructLayout("This is a test!"));
		
		IOMap<Integer, Object> map=provider.getTemp();
		
		Random r=new Random();
		r.setSeed(1);
		for(int i=0;i<4;i++){
			map.put(i, "int("+i+")");
		}
	}
	
}
