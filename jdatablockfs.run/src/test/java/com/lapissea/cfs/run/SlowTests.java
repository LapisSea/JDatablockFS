package com.lapissea.cfs.run;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.HashIOMap;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.cfs.tools.logging.DataLogger;
import com.lapissea.cfs.tools.logging.LoggedMemoryUtils;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class SlowTests{
	
	@Test
	void ioMultiWrite() throws IOException{
		LateInit<DataLogger> logger;
		logger=new LateInit<>(()->DataLogger.Blank.INSTANCE);
//		logger=LoggedMemoryUtils.createLoggerFromConfig();
		
		byte[] baked;
		{
			var d=MemoryData.build().build();
			Cluster.init(d);
			baked=d.readAll();
		}
		
		long   lastTim=0;
		Random r      =new Random(1);
		for(int iter=0;iter<500000;iter++){
			if(System.currentTimeMillis()-lastTim>1000){
				lastTim=System.currentTimeMillis();
				LogUtil.println(iter);
			}
			try{
				List<RandomIO.WriteChunk> allWrites;
				if(iter==0){
					var b=new byte[10];
					Arrays.fill(b, (byte)(1));
					allWrites=List.of(new RandomIO.WriteChunk(1, b));
				}else allWrites=IntStream.range(0, r.nextInt(10)+1).mapToObj(i->{
					var bytes=new byte[15];
					Arrays.fill(bytes, (byte)(i+1));
					return new RandomIO.WriteChunk(r.nextInt(20), bytes);
				}).toList();
				
				Chunk head;
				
				IOInterface mem=LoggedMemoryUtils.newLoggedMemory("default", logger);
				try(var ignored=mem.openIOTransaction()){
					var chunks=new ArrayList<Chunk>();
					
					mem.write(true, baked);
					Cluster c=new Cluster(mem);
					
					var chunkCount=r.nextInt(5)+1;
					for(int i=0;i<chunkCount;i++){
						chunks.add(AllocateTicket.bytes(r.nextInt(10)+1).withNext(ChunkPointer.of(1000)).submit(c));
					}
					
					for(int i=0;i<100;i++){
						var ai=r.nextInt(chunks.size());
						var bi=r.nextInt(chunks.size());
						
						var a=chunks.get(ai);
						var b=chunks.get(bi);
						
						chunks.set(ai, b);
						chunks.set(bi, a);
					}
					
					head=chunks.get(0);
					var last=head;
					for(int i=1;i<chunks.size();i++){
						var next=chunks.get(i);
						last.setNextPtr(next.getPtr());
						last.syncStruct();
						last=next;
					}
					last.setNextPtr(ChunkPointer.NULL);
					last.syncStruct();
				}
				
				for(int i=0;i<allWrites.size();i++){
					var writes=allWrites.subList(0, i+1);

//					for(RandomIO.WriteChunk write : writes){
//						LogUtil.println(write);
//					}
					
					try(var io=head.io()){
						io.writeAtOffsets(writes);
					}
					
					var read=head.readAll();
					
					var valid=new byte[Math.toIntExact(writes.stream().mapToLong(RandomIO.WriteChunk::ioEnd).max().orElseThrow())];
					for(RandomIO.WriteChunk write : writes){
						System.arraycopy(write.data(), 0, valid, Math.toIntExact(write.ioOffset()), write.dataLength());
					}

//					LogUtil.println(IntStream.range(0, valid.length).mapToObj(a->(valid[a]+"")).collect(Collectors.joining()));
//					LogUtil.println(IntStream.range(0, read.length).mapToObj(a->(read[a]+"")).collect(Collectors.joining()));
					
					int finalIter=iter;
					Assertions.assertArrayEquals(read, valid, ()->{
						return finalIter+"\n"+
						       IntStream.range(0, valid.length).mapToObj(a->(valid[a]+"")).collect(Collectors.joining())+"\n"+
						       IntStream.range(0, read.length).mapToObj(a->(read[a]+"")).collect(Collectors.joining());
					});
					
				}
			}catch(AssertionFailedError e){
				throw e;
			}catch(Throwable e){
				throw new RuntimeException(iter+"", e);
			}
		}
		
	}
	
	@Test
	void ioTransaction() throws IOException{
		int cap=50;
		
		IOInterface data  =MemoryData.build().withCapacity(cap+10).build();
		IOInterface mirror=MemoryData.build().withCapacity(cap+10).build();
		
		
		int runIndex=0;
		
		var rand=new Random(1);
		//Dumb brute force all possible edge cases
		for(int run=0;run<50000;run++){
			if(run%10000==0) LogUtil.println(run);
			
			try(var ignored=data.openIOTransaction()){
				var runSize=rand.nextInt(40);
				for(int j=1;j<runSize+1;j++){
					runIndex++;
					var failS="failed on run "+runIndex;
					
					if(rand.nextFloat()<0.1){
						var newSiz=rand.nextInt(cap*2)+21;
						
						try(var io=mirror.io()){
							io.setCapacity(newSiz);
						}
						try(var io=data.io()){
							io.setCapacity(newSiz);
						}
					}else{
						
						int off=rand.nextInt((int)data.getIOSize()-10);
						int siz=rand.nextInt(10);
						
						byte[] buf=new byte[siz];
						Arrays.fill(buf, (byte)j);
						
						mirror.write(off, false, buf);
						data.write(off, false, buf);
						
						Assertions.assertArrayEquals(mirror.read(off+1, 9),
						                             data.read(off+1, 9),
						                             failS);
					}
					
					Assertions.assertEquals(mirror.getIOSize(), data.getIOSize(), failS);
					
					for(int i=0;i<100;i++){
						var rSiz  =rand.nextInt(20);
						var rOff  =rand.nextInt((int)(data.getIOSize()-rSiz));
						int finalI=i;
						Assertions.assertArrayEquals(mirror.read(rOff, rSiz),
						                             data.read(rOff, rSiz), ()->failS+" "+finalI);
					}
					
					check(mirror, data, failS);
				}
			}catch(Throwable e){
				throw new RuntimeException("failed on run "+runIndex, e);
			}
			check(mirror, data, "failed after cycle "+run);
		}
	}
	
	private void check(IOInterface expected, IOInterface data, String s){
		byte[] m, d;
		try{
			m=expected.readAll();
			d=data.readAll();
		}catch(Throwable e){
			throw new RuntimeException(s, e);
		}
		Assertions.assertArrayEquals(m, d, ()->s+"\n"+
		                                       HexFormat.of().formatHex(m)+"\n"+
		                                       HexFormat.of().formatHex(d)+"\n"
		);
	}
	
	@Test
	void bigMap(TestInfo info) throws IOException{
		TestUtils.testCluster(info, provider->{
			var map=provider.getRootProvider().<IOMap<Object, Object>>builder().withType(TypeLink.of(HashIOMap.class, Object.class, Object.class)).withId("map").request();
			
			var splitter=Splitter.map(map, new ReferenceMemoryIOMap<>(), TestUtils::checkCompliance);
			
			int i=0;
			while(provider.getSource().getIOSize()<NumberSize.SHORT.maxSize){
				splitter.put(i, "int("+i+")");
				if(i%100==0) LogUtil.println(i, provider.getSource().getIOSize()/(float)NumberSize.SHORT.maxSize);
				i++;
			}
			LogUtil.println(i, provider.getSource().getIOSize()/(float)NumberSize.SHORT.maxSize);
		});
	}
	
}
