package com.lapisseqa.cfs.run;

import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.objects.NumberSize;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

public class BitTests{
	
	@Test
	void bitStreamIntegrity() throws IOException{
		
		Random r=new Random(1);
		for(int i=0;i<10000;i++){
			var bs=new boolean[i];
			for(int j=0;j<bs.length;j++){
				bs[j]=r.nextBoolean();
			}
			
			var buff=new ByteArrayOutputStream();
			try(var out=new BitOutputStream(new ContentOutputStream.Wrapp(buff))){
				out.writeBits(bs);
			}
			
			var rbs=new boolean[bs.length];
			try(var in=new BitInputStream(new ContentInputStream.BA(buff.toByteArray()))){
				in.readBits(rbs);
			}
			
			Assertions.assertArrayEquals(bs, rbs, ""+i);
		}
	}
	
	@Test
	void bitFlagIntegrity() throws IOException{
		
		Random r=new Random(1);
		for(int i=0;i<10000;i++){
			for(var siz : NumberSize.FLAG_INFO){
				var bs=new boolean[r.nextInt(siz.bits()+1)];
				for(int j=0;j<bs.length;j++){
					bs[j]=r.nextBoolean();
				}
				
				var buff=new ByteArrayOutputStream();
				try(var out=new FlagWriter.AutoPop(siz, new ContentOutputStream.Wrapp(buff))){
					out.writeBits(bs);
				}
				
				var rbs=new boolean[bs.length];
				try(var in=FlagReader.read(new ContentInputStream.BA(buff.toByteArray()), siz)){
					in.readBits(rbs);
				}
				
				Assertions.assertArrayEquals(bs, rbs, siz+" "+i);
			}
		}
	}
	
}
