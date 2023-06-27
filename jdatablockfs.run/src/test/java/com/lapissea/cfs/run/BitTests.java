package com.lapissea.cfs.run;

import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.testng.Assert.assertEquals;

public class BitTests{
	
	@Test
	void bitStreamIntegrity(){
		
		Random r = new Random(1);
		for(int i = 0; i<10000; i++){
			boolean[] bs;
			boolean[] rbs;
			try{
				
				bs = new boolean[i];
				for(int j = 0; j<bs.length; j++){
					bs[j] = r.nextBoolean();
				}
				
				var buff = new ByteArrayOutputStream();
				try(var out = new BitOutputStream(new ContentOutputStream.Wrapp(buff))){
					out.writeBits(bs);
				}
				rbs = new boolean[bs.length];
				try(var in = new BitInputStream(new ContentInputStream.BA(buff.toByteArray()), rbs.length)){
					in.readBits(rbs);
				}
			}catch(Throwable e){
				throw new RuntimeException("failed iter " + i, e);
			}
			
			assertEquals(rbs, bs, "" + i);
		}
	}
	
	@Test
	void bitFlagIntegrity() throws IOException{
		
		Random r = new Random(1);
		for(int i = 0; i<10000; i++){
			for(var siz : NumberSize.FLAG_INFO){
				var bs = new boolean[r.nextInt(siz.bits() + 1)];
				for(int j = 0; j<bs.length; j++){
					bs[j] = r.nextBoolean();
				}
				
				var buff = new ByteArrayOutputStream();
				try(var out = new FlagWriter.AutoPop(siz, new ContentOutputStream.Wrapp(buff))){
					out.writeBits(bs);
				}
				
				var rbs = new boolean[bs.length];
				try(var in = FlagReader.read(new ContentInputStream.BA(buff.toByteArray()), siz)){
					in.readBits(rbs);
				}
				
				assertEquals(rbs, bs, siz + " " + i);
			}
		}
	}
	
}
