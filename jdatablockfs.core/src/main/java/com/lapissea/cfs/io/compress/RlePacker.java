package com.lapissea.cfs.io.compress;

import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.util.Arrays;

public final class RlePacker implements Packer{
	
	private static final int REPEAT_1B           =0b00;
	private static final int REPEAT_1B_PACKED_LEN=0b01;
	private static final int RAW_BLOCK           =0b10;
	private static final int RAW_BLOCK_PACKED_LEN=0b11;
	
	private static final int MAX_PACK_SIZE=0b111111;
	
	@Override
	public byte[] pack(byte[] data){
		if(data.length==0) return data;
		
		var builder =new ContentOutputBuilder(data.length);
		var rawBlock=new ContentOutputBuilder(32);
		
		for(int i=0;i<data.length;i++){
			byte b=data[i];
			int  j=i+1;
			for(;j<data.length;j++){
				if(data[j]!=b) break;
			}
			int repeats=j-i;
			i=j-1;
			
			if(repeats<=2){
				rawBlock.write(b);
				if(repeats==2) rawBlock.write(b);
				continue;
			}
			
			flushRaw(builder, rawBlock);
			writeRepeats(builder, b, repeats);
		}
		
		flushRaw(builder, rawBlock);
		return builder.toByteArray();
	}
	
	private void writeRepeats(ContentOutputBuilder builder, byte b, int repeats){
		if(repeats<=MAX_PACK_SIZE){
			builder.write(REPEAT_1B_PACKED_LEN|(repeats<<2));
			builder.write(b);
		}else{
			var siz=NumberSize.bySize(repeats);
			builder.write(REPEAT_1B|(siz.ordinal()<<2));
			writeSiz(builder, siz, repeats);
			builder.write(b);
		}
	}
	
	private void writeSiz(ContentOutputBuilder builder, NumberSize siz, int num){
		try{
			siz.write(builder, num);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	private void flushRaw(ContentOutputBuilder builder, ContentOutputBuilder rawBlock){
		if(rawBlock.size()==0) return;
		var size=rawBlock.size();
		
		if(size<=MAX_PACK_SIZE){
			builder.write(RAW_BLOCK_PACKED_LEN|(size<<2));
		}else{
			var siz=NumberSize.bySize(size);
			builder.write(RAW_BLOCK|(siz.ordinal()<<2));
			writeSiz(builder, siz, size);
		}
		rawBlock.writeTo(builder);
		rawBlock.reset();
	}
	
	@Override
	public byte[] unpack(byte[] packedData){
		if(packedData.length==0) return packedData;
		try{
			var builder=new ContentOutputBuilder();
			var data   =new ContentInputStream.BA(packedData);
			while(data.available()>0){
				int head=data.readUnsignedInt1();
				switch(head&0b11){
					case RAW_BLOCK -> {
						var siz=Math.toIntExact(NumberSize.ordinal(head>>2).read(data));
						builder.write(data.readNBytes(siz));
					}
					case RAW_BLOCK_PACKED_LEN -> {
						var siz=head>>2;
						builder.write(data.readNBytes(siz));
					}
					case REPEAT_1B_PACKED_LEN -> {
						var    siz  =head>>2;
						byte   b    =data.readInt1();
						byte[] block=new byte[siz];
						Arrays.fill(block, b);
						builder.write(block);
					}
					case REPEAT_1B -> {
						var    siz  =Math.toIntExact(NumberSize.ordinal(head>>2).read(data));
						byte   b    =data.readInt1();
						byte[] block=new byte[siz];
						Arrays.fill(block, b);
						builder.write(block);
					}
					default -> throw new IllegalStateException();
				}
			}
			return builder.toByteArray();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
}
