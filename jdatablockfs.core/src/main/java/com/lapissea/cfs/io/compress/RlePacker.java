package com.lapissea.cfs.io.compress;

import com.lapissea.cfs.internal.MemPrimitive;
import com.lapissea.cfs.objects.NumberSize;

import java.util.Arrays;

public final class RlePacker implements Packer{
	
	private static final int REPEAT_1B           =0b00;
	private static final int REPEAT_1B_PACKED_LEN=0b01;
	private static final int RAW_BLOCK           =0b10;
	private static final int RAW_BLOCK_PACKED_LEN=0b11;
	
	private static final int MAX_PACK_SIZE=0b111111;
	
	private static final class Packer{
		private final byte[] data;
		private       byte[] packed;
		private       int    packedSize=0;
		private       byte[] raw       =new byte[32];
		private       int    rawSize   =0;
		
		private Packer(byte[] data){
			this.data=data;
			packed=new byte[data.length/5];
		}
		
		private byte[] compress(){
			for(int i=0;i<data.length;i++){
				byte b=data[i];
				int  j=i+1;
				for(;j<data.length;j++){
					if(data[j]!=b) break;
				}
				int repeats=j-i;
				
				if(repeats<=2){
					var last=b;
					for(;j<data.length;j++){
						var d1=data[j];
						if(d1==last){
							break;
						}
						last=d1;
					}
					var len  =j-i;
					var start=j-len;
					if(j==data.length&&rawSize==0){
						var siz    =NumberSize.bySize(len);
						var needed =1+siz.bytes+len;
						var needSum=packedSize+needed;
						if(packed.length<needSum){
							packed=Arrays.copyOf(packed, needSum);
						}
						int pos=packedSize;
						packedSize+=needed;
						packed[pos++]=(byte)(RAW_BLOCK|(siz.ordinal()<<2));
						writeSiz(siz, pos, len);
						pos+=siz.bytes;
						System.arraycopy(data, start, packed, pos, len);
						return makeFinal();
					}
					if(raw.length<len+rawSize) raw=Arrays.copyOf(raw, Math.max(raw.length*2, len+rawSize));
					System.arraycopy(data, start, raw, rawSize, len);
					rawSize+=len;
					i=j-1;
					continue;
				}
				i=j-1;
				
				flushRaw();
				writeRepeats(b, repeats);
			}
			
			flushRaw();
			
			return makeFinal();
		}
		private byte[] makeFinal(){
			return packed.length==packedSize?packed:Arrays.copyOf(packed, packedSize);
		}
		private void checkSiz(int extra){
			var needSum=packedSize+extra;
			if(packed.length<needSum){
				packed=Arrays.copyOf(packed, Math.max(needSum, packed.length*2));
			}
		}
		private void writeRepeats(byte b, int repeats){
			if(repeats<=MAX_PACK_SIZE){
				checkSiz(2);
				var s=packedSize;
				packedSize+=2;
				packed[s]=(byte)(REPEAT_1B_PACKED_LEN|(repeats<<2));
				packed[s+1]=b;
			}else{
				var siz  =NumberSize.bySize(repeats);
				int extra=2+siz.bytes;
				checkSiz(extra);
				var s=packedSize;
				packedSize+=extra;
				
				packed[s]=(byte)(REPEAT_1B|(siz.ordinal()<<2));
				writeSiz(siz, s+1, repeats);
				packed[s+1+siz.bytes]=b;
			}
		}
		
		private void writeSiz(NumberSize siz, int pos, int num){
			switch(siz){
				case VOID -> {}
				case BYTE -> packed[pos]=(byte)num;
				default -> MemPrimitive.setWord(num, packed, pos, siz.bytes);
			}
		}
		
		private void flushRaw(){
			if(rawSize==0) return;
			int pos=packedSize;
			if(rawSize<=MAX_PACK_SIZE){
				checkAndAdvance(1+rawSize);
				packed[pos++]=(byte)(RAW_BLOCK_PACKED_LEN|(rawSize<<2));
			}else{
				var siz=NumberSize.bySize(rawSize);
				checkAndAdvance(1+siz.bytes+rawSize);
				packed[pos++]=(byte)(RAW_BLOCK|(siz.ordinal()<<2));
				writeSiz(siz, pos, rawSize);
				pos+=siz.bytes;
			}
			System.arraycopy(raw, 0, packed, pos, rawSize);
			rawSize=0;
		}
		private void checkAndAdvance(int extra){
			checkSiz(extra);
			packedSize+=extra;
		}
	}
	
	private static final class Unpacker{
		private final byte[] packedData;
		
		
		private Unpacker(byte[] packedData){
			this.packedData=packedData;
		}
		
		private int readLen(NumberSize siz, int pos){
			return switch(siz){
				case VOID -> 0;
				case BYTE -> packedData[pos]&0xFF;
				default -> (int)MemPrimitive.getWord(packedData, pos, siz.bytes);
			};
		}
		
		private int scanSize(){
			int packedPos =0;
			int rawSizeSum=0;
			while(packedPos<packedData.length){
				int head   =packedData[packedPos++]&0xFF;
				var blockId=head&0b11;
				var dataNum=head>>2;
				switch(blockId){
					case REPEAT_1B -> {
						var siz=NumberSize.ordinal(dataNum);
						var len=readLen(siz, packedPos);
						packedPos+=1+siz.bytes;
						rawSizeSum+=len;
					}
					case REPEAT_1B_PACKED_LEN -> {
						packedPos++;
						rawSizeSum+=dataNum;
					}
					case RAW_BLOCK -> {
						var siz=NumberSize.ordinal(dataNum);
						var len=readLen(siz, packedPos);
						packedPos+=siz.bytes+len;
						rawSizeSum+=len;
					}
					case RAW_BLOCK_PACKED_LEN -> {
						packedPos+=dataNum;
						rawSizeSum+=dataNum;
					}
				}
			}
			return rawSizeSum;
		}
		
		private byte[] raw;
		private int    rawPos   =0;
		private int    packedPos=0;
		
		private byte[] decompress(){
			int size=scanSize();
			raw=new byte[size];
			
			while(packedPos<packedData.length){
				int head   =packedData[packedPos++]&0xFF;
				var blockId=head&0b11;
				var dataNum=head>>2;
				switch(blockId){
					case REPEAT_1B -> copyIncByte(readLenInc(dataNum));
					case REPEAT_1B_PACKED_LEN -> copyIncByte(dataNum);
					case RAW_BLOCK -> copyInc(readLenInc(dataNum));
					case RAW_BLOCK_PACKED_LEN -> copyInc(dataNum);
				}
			}
			
			return raw;
		}
		private int readLenInc(int ordinal){
			var siz=NumberSize.ordinal(ordinal);
			var len=readLen(siz, packedPos);
			packedPos+=siz.bytes;
			return len;
		}
		private void copyIncByte(int len){
			byte b=packedData[packedPos++];
			for(int i=rawPos, to=rawPos+len;i<to;i++){
				raw[i]=b;
			}
			rawPos+=len;
		}
		private void copyInc(int len){
			System.arraycopy(packedData, packedPos, raw, rawPos, len);
			rawPos+=len;
			packedPos+=len;
		}
	}
	
	@Override
	public byte[] pack(byte[] data){
		if(data.length==0) return data;
		return new Packer(data).compress();
	}
	
	@Override
	public byte[] unpack(byte[] packedData){
		if(packedData.length==0) return packedData;
		return new Unpacker(packedData).decompress();
	}
}
