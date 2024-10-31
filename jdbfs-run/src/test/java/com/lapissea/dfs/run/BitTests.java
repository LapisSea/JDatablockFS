package com.lapissea.dfs.run;

import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;

import static org.testng.Assert.assertEquals;

public class BitTests{
	
	@Test
	void bitStreamIntegrity(){
		TestUtils.randomBatch(10000, (r, actionIndex) -> {
			int i = Math.toIntExact(actionIndex);
			
			boolean[] bs;
			boolean[] rbs;
			bs = new boolean[i<1000? i : r.nextInt(10000)];
			for(int j = 0; j<bs.length; j++){
				bs[j] = r.nextBoolean();
			}
			
			var buff = new byte[BitUtils.bitsToBytes(bs.length)];
			try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
				int remaining = bs.length, pos = 0;
				while(remaining>0){
					var l     = r.nextInt(remaining) + 1;
					var chunk = new boolean[l];
					System.arraycopy(bs, pos, chunk, 0, l);
					out.writeBits(chunk);
					pos += l;
					remaining -= l;
				}
			}
			rbs = new boolean[bs.length];
			try(var in = new BitInputStream(new ContentInputStream.BA(buff), rbs.length)){
				in.readBits(rbs);
			}
			
			assertEquals(rbs, bs, "" + i);
		});
	}
	
	@Test
	void bitFlagIntegrity(){
		TestUtils.randomBatch(10000, (r, actionIndex) -> {
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
				
				assertEquals(rbs, bs, siz + " " + actionIndex);
			}
		});
	}
	
}
