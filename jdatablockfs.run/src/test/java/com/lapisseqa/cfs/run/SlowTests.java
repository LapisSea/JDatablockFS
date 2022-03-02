package com.lapisseqa.cfs.run;

import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.IOMap;
import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

public class SlowTests{
	
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
	private void check(IOInterface expected, IOInterface data, String s) throws IOException{
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
			IOMap<Object, Object> map=provider.getTemp();
			
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
