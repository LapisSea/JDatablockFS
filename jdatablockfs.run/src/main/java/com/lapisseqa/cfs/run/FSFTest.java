package com.lapisseqa.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.GenericContainer;
import com.lapissea.cfs.objects.collections.ContiguousIOList;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.stream.LongStream;

class FSFTest{
	
	public static void main(String[] args){
//		LogUtil.Init.attach(USE_CALL_POS|USE_TABULATED_HEADER);
		LogUtil.Init.attach(0);
		
		try{
			String               sessionName="default";
			LateInit<DataLogger> logger     =LoggedMemoryUtils.createLoggerFromConfig();
			MemoryData<?>        mem        =LoggedMemoryUtils.newLoggedMemory(sessionName, logger);
			
			try{
				Cluster.init(mem);
				Cluster cluster=new Cluster(mem);
				
				doTests(cluster);
			}finally{
				logger.block();
				mem.onWrite.log(mem, LongStream.of());
				logger.get().destroy();
			}
			
		}catch(Throwable e){
			e.printStackTrace();
		}
	}
	
	static class Dummy extends IOInstance<Dummy>{
		
		@IOValue
		int dummyValue;
		
		public Dummy(){
		}
		public Dummy(int dummyValue){
			this.dummyValue=dummyValue;
		}
	}
	
	private static void doTests(Cluster provider) throws IOException{
		
		var roots=provider.getRootReferences();
		
		var chunk=AllocateTicket.bytes(64).submit(provider);
		
		var ref=chunk.getPtr().makeReference(0);
		var typ=TypeDefinition.of(ContiguousIOList.class, Dummy.class);
		
		ContiguousIOList<Dummy> list=new ContiguousIOList<>(provider, ref, typ);
		
		list.add(new Dummy(69));
		list.add(new Dummy(420));
		
		roots.add(new GenericContainer<>(list));


//		var chunk=AllocateTicket.bytes(64).submit(provider);
//
//		var ref=chunk.getPtr().makeReference(0);
//		var typ=TypeDefinition.of(HashIOMap.class, Integer.class, Integer.class);
//
//		IOMap<Integer, Integer> map=new HashIOMap<>(provider, ref, typ);
//
//		map.put(1, 11);
//		map.put(2, 12);
//		map.put(3, 13);
//		map.put(16, 21);
//		map.put(17, 22);
//		map.put(18, 23);
//
//
//		IOMap<Integer, Integer> read=new HashIOMap<>(provider, ref, typ);
//
//		LogUtil.println(map);
//		LogUtil.println(read);
//
//		assert map.equals(read);
	}
	
}
