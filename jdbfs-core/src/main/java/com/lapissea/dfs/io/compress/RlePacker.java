package com.lapissea.dfs.io.compress;

import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.dfs.objects.NumberSize;

import java.io.IOException;
import java.util.Arrays;

public final class RlePacker implements Packer{
	
	private static final int REPEAT_1B            = 0b00;
	private static final int REPEAT_1B_PACKED_LEN = 0b01;
	private static final int RAW_BLOCK            = 0b10;
	private static final int RAW_BLOCK_PACKED_LEN = 0b11;
	
	private static final int MAX_PACK_SIZE = 0b111111;
	
	private static final class Packer{
		private final byte[]     data;
		private final int        rawLen;
		private final NumberSize rawSiz;
		private final int        headerLen;
		private       byte[]     packed;
		private       int        packedSize;
		private       byte[]     raw        = new byte[32];
		private       int        rawSize    = 0;
		
		private Packer(byte[] data){
			this.data      = data;
			this.rawLen    = data.length;
			this.rawSiz    = NumberSize.bySize(rawLen);
			this.headerLen = 1 + rawSiz.bytes;
			packed         = new byte[rawLen/5 + headerLen];
			packedSize     = headerLen;
		}
		
		private byte[] compress(){
			for(int i = 0; i<data.length; i++){
				byte b = data[i];
				int  j = i + 1;
				//Check the i'th byte and walk until the end or change of byte
				for(; j<data.length; j++){
					if(data[j] != b) break;
				}
				int repeats = j - i;
				
				if(repeats<=2){
					var last = b;
					//Continue walking on j from i until data stops changing. This marks the end of a raw block
					for(; j<data.length; j++){
						var d1 = data[j];
						if(d1 == last){
							break;
						}
						last = d1;
					}
					var len   = j - i;
					var start = j - len;
					
					//Optimized final raw block flush. Remove need for array copy in makeFinal
					if(j == data.length && rawSize == 0){
						var siz     = NumberSize.bySize(len);
						var needed  = 1 + siz.bytes + len;
						var needSum = packedSize + needed;
						if(packed.length<needSum){
							packed = Arrays.copyOf(packed, needSum);
						}
						int pos = packedSize;
						packedSize += needed;
						packed[pos++] = (byte)(RAW_BLOCK|(siz.ordinal()<<2));
						writeSiz(siz, pos, len);
						pos += siz.bytes;
						System.arraycopy(data, start, packed, pos, len);
						return makeFinal();
					}
					
					//append raw block
					if(raw.length<len + rawSize) raw = Arrays.copyOf(raw, Math.max(raw.length*2, len + rawSize));
					System.arraycopy(data, start, raw, rawSize, len);
					rawSize += len;
					i = j - 1;
					continue;
				}
				i = j - 1;
				
				flushRaw();
				writeRepeats(b, repeats);
			}
			
			flushRaw();
			
			return makeFinal();
		}
		private byte[] makeFinal(){
			packed[0] = (byte)rawSiz.ordinal();
			WordIO.setWord(rawLen, packed, 1, rawSiz.bytes);
			return packed.length == packedSize? packed : Arrays.copyOf(packed, packedSize);
		}
		private void checkSiz(int extra){
			var needSum = packedSize + extra;
			if(packed.length<needSum){
				packed = Arrays.copyOf(packed, Math.max(needSum, packed.length*2));
			}
		}
		private void writeRepeats(byte b, int repeats){
			if(repeats<=MAX_PACK_SIZE){
				checkSiz(2);
				var s = packedSize;
				packedSize += 2;
				packed[s] = (byte)(REPEAT_1B_PACKED_LEN|(repeats<<2));
				packed[s + 1] = b;
			}else{
				var siz   = NumberSize.bySize(repeats);
				int extra = 2 + siz.bytes;
				checkSiz(extra);
				var s = packedSize;
				packedSize += extra;
				
				packed[s] = (byte)(REPEAT_1B|(siz.ordinal()<<2));
				writeSiz(siz, s + 1, repeats);
				packed[s + 1 + siz.bytes] = b;
			}
		}
		
		private void writeSiz(NumberSize siz, int pos, int num){
			WordIO.setWord(num, packed, pos, siz.bytes);
		}
		
		private void flushRaw(){
			if(rawSize == 0) return;
			int pos = packedSize;
			if(rawSize<=MAX_PACK_SIZE){
				checkAndAdvance(1 + rawSize);
				packed[pos++] = (byte)(RAW_BLOCK_PACKED_LEN|(rawSize<<2));
			}else{
				var siz = NumberSize.bySize(rawSize);
				checkAndAdvance(1 + siz.bytes + rawSize);
				packed[pos++] = (byte)(RAW_BLOCK|(siz.ordinal()<<2));
				writeSiz(siz, pos, rawSize);
				pos += siz.bytes;
			}
			System.arraycopy(raw, 0, packed, pos, rawSize);
			rawSize = 0;
		}
		private void checkAndAdvance(int extra){
			checkSiz(extra);
			packedSize += extra;
		}
	}
	
	private static final class Unpacker{
		private final byte[] packedData;
		private int headerRawLen;
		private int blockStart;
		
		
		private Unpacker(byte[] packedData){
			this.packedData = packedData;
		}
		
		private void readHeader() throws IOException{
			int ord = readByteAt(0);
			if(ord < 1 || ord >= NumberSize.values().length){
				throw new IOException("Corrupt RLE payload: invalid raw-length size ordinal " + ord);
			}
			var siz       = NumberSize.ordinal(ord);
			int headerLen = 1 + siz.bytes;
			if(headerLen > packedData.length){
				throw new IOException("Corrupt RLE payload: raw-length field runs past end");
			}
			long rawLen = WordIO.getWord(packedData, 1, siz.bytes);
			if(rawLen < 0 || rawLen > Integer.MAX_VALUE){
				throw new IOException("Corrupt RLE payload: raw length out of range " + rawLen);
			}
			headerRawLen = (int)rawLen;
			blockStart   = headerLen;
		}
		
		private int readByteAt(int pos) throws IOException{
			if(pos < 0 || pos >= packedData.length){
				throw new IOException("Corrupt RLE payload: read past end at " + pos);
			}
			return packedData[pos]&0xFF;
		}
		private NumberSize numSize(int dataNum) throws IOException{
			if(dataNum < 0 || dataNum >= NumberSize.values().length){
				throw new IOException("Corrupt RLE payload: invalid dataNum " + dataNum);
			}
			return NumberSize.ordinal(dataNum);
		}
		private int readLen(NumberSize siz, int pos) throws IOException{
			if(pos < 0 || pos + siz.bytes > packedData.length){
				throw new IOException("Corrupt RLE payload: length field runs past end at " + pos);
			}
			return (int)WordIO.getWord(packedData, pos, siz.bytes);
		}
		
		private int scanSize() throws IOException{
			int  packedPos  = blockStart;
			long rawSizeSum = 0;
			while(packedPos<packedData.length){
				int head    = readByteAt(packedPos++);
				var blockId = head&0b11;
				var dataNum = head>>2;
				switch(blockId){
					case REPEAT_1B -> {
						var siz = numSize(dataNum);
						var len = readLen(siz, packedPos);
						packedPos += 1 + siz.bytes;
						rawSizeSum += len;
					}
					case REPEAT_1B_PACKED_LEN -> {
						readByteAt(packedPos++);
						rawSizeSum += dataNum;
					}
					case RAW_BLOCK -> {
						var siz = numSize(dataNum);
						var len = readLen(siz, packedPos);
						if(packedPos + siz.bytes + len > packedData.length){
							throw new IOException("Corrupt RLE payload: raw block runs past end");
						}
						packedPos += siz.bytes + len;
						rawSizeSum += len;
					}
					case RAW_BLOCK_PACKED_LEN -> {
						if(packedPos + dataNum > packedData.length){
							throw new IOException("Corrupt RLE payload: raw packed-len block runs past end");
						}
						packedPos += dataNum;
						rawSizeSum += dataNum;
					}
				}
			}
			if(rawSizeSum > Integer.MAX_VALUE){
				throw new IOException("Corrupt RLE payload: decoded size overflows int");
			}
			return (int)rawSizeSum;
		}
		
		private byte[] raw;
		private int    rawPos    = 0;
		private int    packedPos = 0;
		
		private byte[] decompress() throws IOException{
			//2 pass scan. First scan for size of compressed array then create it.
			// Apparently faster in benchmarks
			readHeader();
			int size = scanSize();
			if(size != headerRawLen){
				throw new IOException("Corrupt RLE payload: decoded size " + size + " != declared raw length " + headerRawLen);
			}
			raw = new byte[size];
			packedPos = blockStart;
			
			while(packedPos<packedData.length){
				int head    = readByteAt(packedPos++);
				var blockId = head&0b11;
				var dataNum = head>>2;
				switch(blockId){
					case REPEAT_1B -> copyIncByte(readLenInc(dataNum));
					case REPEAT_1B_PACKED_LEN -> copyIncByte(dataNum);
					case RAW_BLOCK -> copyInc(readLenInc(dataNum));
					case RAW_BLOCK_PACKED_LEN -> copyInc(dataNum);
				}
			}
			
			if(rawPos != size){
				throw new IOException("Corrupt RLE payload: decoded size mismatch " + rawPos + " != " + size);
			}
			return raw;
		}
		private int readLenInc(int ordinal) throws IOException{
			var siz = numSize(ordinal);
			var len = readLen(siz, packedPos);
			packedPos += siz.bytes;
			return len;
		}
		private void copyIncByte(int len) throws IOException{
			if(len < 0){
				throw new IOException("Corrupt RLE payload: negative repeat length " + len);
			}
			byte b = (byte)readByteAt(packedPos++);
			for(int i = rawPos, to = rawPos + len; i<to; i++){
				raw[i] = b;
			}
			rawPos += len;
		}
		private void copyInc(int len) throws IOException{
			if(len < 0 || packedPos + len > packedData.length || rawPos + len > raw.length){
				throw new IOException("Corrupt RLE payload: raw block out of bounds");
			}
			System.arraycopy(packedData, packedPos, raw, rawPos, len);
			rawPos += len;
			packedPos += len;
		}
	}
	
	@Override
	public byte[] pack(byte[] data){
		if(data.length == 0) return data;
		return new Packer(data).compress();
	}
	
	@Override
	public byte[] unpack(byte[] packedData) throws IOException{
		if(packedData.length == 0) return packedData;
		return new Unpacker(packedData).decompress();
	}
	@Override
	public String name(){
		return "RLE";
	}
}
