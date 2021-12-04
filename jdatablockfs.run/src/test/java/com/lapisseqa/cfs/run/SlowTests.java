package com.lapisseqa.cfs.run;

import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.util.LogUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

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
					
					int off=rand.nextInt(cap);
					int siz=rand.nextInt(10);
					
					byte[] buf=new byte[siz];
					Arrays.fill(buf, (byte)j);

//					LogUtil.println("================================");
					
					mirror.write(off, false, buf);
//					LogUtil.println("  ", HexFormat.of().formatHex(mirror.readAll()).replace('0', '.'));
//					LogUtil.println(HexFormat.of().formatHex(buf).replace('0', '.'));

//					LogUtil.println("a ", HexFormat.of().formatHex(data.readAll()).replace('0', '.'));
					data.write(off, false, buf);
//					LogUtil.println("b ", HexFormat.of().formatHex(data.readAll()).replace('0', '.'));
					
					for(int i=0;i<100;i++){
						var rSiz=rand.nextInt(20);
						var rOff=rand.nextInt(cap+10-rSiz);
						Assertions.assertArrayEquals(mirror.read(rOff, rSiz),
						                             data.read(rOff, rSiz), ""+i);
					}
					
					Assertions.assertArrayEquals(mirror.read(off+1, 9),
					                             data.read(off+1, 9));
					check(mirror, data, "failed on run "+runIndex);
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
}
