package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.exceptions.IllegalBitValue;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.EOFException;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.random.RandomGenerator;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.internal.MyUnsafe.UNSAFE;
import static com.lapissea.dfs.io.bit.BitUtils.bitsToBytes;
import static com.lapissea.dfs.io.bit.BitUtils.makeMask;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

public enum Encoding{
	BASE_16_UPPER(new TableCoding(
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
		'A', 'B', 'C', 'D', 'E', 'F'
	)),
	BASE_16_LOWER(new TableCoding(
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
		'a', 'b', 'c', 'd', 'e', 'f'
	)),
	BASE_32_UPPER(new TableCoding(
		'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
		'0', '1', '2', '3', '4', '5'
	)),
	BASE_32_LOWER(new TableCoding(
		'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
		'0', '1', '2', '3', '4', '5'
	)),
	BASE_64(new TableCoding(
		'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
		'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
		'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
	)),
	BASE_64_CNAME(new TableCoding(
		//Variation of base 64 optimized for storing class names and fields.
		'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
		'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
		'0', '1', '2', '3', '4', '5', '_', '$', '[', ';', '.', FieldNames.GENERATED_FIELD_SEPARATOR
	)),
	LATIN1(new Latin1Coding()),
	UTF8(new UTF8Coding());
	
	sealed interface Coding{
		void read(ContentInputStream src, CharBuffer dest) throws IOException;
		void write(ContentWriter dest, String str) throws IOException;
		boolean canEncode(String str);
		int calcSize(String str);
		float sizeWeight();
		String randomString(RandomGenerator random, int minLength, int maxLength);
	}
	
	private static final class UTF8Coding implements Coding{
		@Override
		public void read(ContentInputStream src, CharBuffer dest) throws IOException{
			int charCount = dest.remaining();
			int remaining = charCount;
			
			var decoder = UTF_8.newDecoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT);
			var reader  = Channels.newReader(Channels.newChannel(src), decoder, (int)(Math.min(1024, charCount)*1.1));
			while(remaining>0){
				int read = reader.read(dest);
				if(read == -1) throw new EOFException();
				remaining -= read;
			}
		}
		
		@Override
		public void write(ContentWriter dest, String str) throws IOException{
			var en = UTF_8.newEncoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT);
			var b  = en.encode(CharBuffer.wrap(str));
			dest.write(b.array(), 0, b.limit());
		}
		@Override
		public boolean canEncode(String str){
			return UTF_8.newEncoder().canEncode(str);
		}
		@Override
		public int calcSize(String str){
			int count = 0;
			for(int i = 0, len = str.length(); i<len; i++){
				char ch = str.charAt(i);
				if(ch<=0x7F) count++;
				else if(ch<=0x7FF) count += 2;
				else if(Character.isHighSurrogate(ch)){
					count += 4;
					++i;
				}else count += 3;
				
			}
			return count;
		}
		@Override
		public float sizeWeight(){ return 1; }
		@Override
		public String randomString(RandomGenerator random, int minLength, int maxLength){
			return generateByRange(this, random, minLength, maxLength, 600);
		}
	}
	
	private static final class Latin1Coding implements Coding{
		@Override
		public void read(ContentInputStream src, CharBuffer dest) throws IOException{
			int charCount = dest.remaining();
			var buff      = src.readInts1(charCount);
			
			for(byte b : buff){
				dest.put((char)Byte.toUnsignedInt(b));
			}
		}
		
		@Override
		public void write(ContentWriter dest, String str) throws IOException{
			dest.write(str.getBytes(ISO_8859_1));
		}
		@Override
		public boolean canEncode(String str){
			return isLatin1Compatible(str);
		}
		@Override
		public int calcSize(String str){
			return str.length();
		}
		@Override
		public float sizeWeight(){ return 1; }
		@Override
		public String randomString(RandomGenerator random, int minLength, int maxLength){
			return generateByRange(this, random, minLength, maxLength, 255);
		}
	}
	
	private static final class TableCoding implements Coding{
		
		private static final class Range{
			private char from, to;
			private Range(char from, char to){
				this.from = from;
				this.to = to;
			}
			@Override
			public String toString(){ return from + "-" + to; }
		}
		
		private static final int blockCharCount = 8;
		
		private final byte[] indexTable;
		private final char[] chars, ranges;
		private final int offset, bits;
		private final int blockBytes;
		
		private final BitSet validPoints;
		
		private TableCoding(char... table){
			assert table.length<=Byte.MAX_VALUE;
			
			int bits = 1;
			while(table.length>1<<bits) bits++;
			assert table.length == (1<<bits);
			
			int offset = Iters.range(0, table.length).map(i -> table[i]).min(0);
			int end    = Iters.range(0, table.length).map(i -> table[i]).max(-2) + 1;
			
			var indexTable = new byte[end - offset];
			Arrays.fill(indexTable, (byte)0xFF);
			
			for(int i = 0, j = table.length; i<j; i++){
				char c = table[i];
				indexTable[c - offset] = (byte)i;
			}
			var ranges = buildRanges(table);
			
			int blockCharCount = calcOptimizedBlockCount(bits);
			assert blockCharCount == TableCoding.blockCharCount;
			int blockBytes = blockCharCount*bits/Byte.SIZE;
			
			this.indexTable = indexTable;
			this.offset = offset;
			this.chars = table.clone();
			this.bits = bits;
			this.ranges = Iters.from(ranges).flatMapToInt(r -> Iters.ofInts(r.from, r.to)).toCharArray();
			this.blockBytes = blockBytes;
			
			validPoints = new BitSet();
			for(var c : chars) validPoints.set(c);
		}
		
		private static int calcOptimizedBlockCount(int bits){
			//First find any number of characters whose bit sum is divisible by 8
			int optimizedBlockCount = 1;
			while((optimizedBlockCount*bits)%Byte.SIZE != 0){
				optimizedBlockCount++;
				if(optimizedBlockCount*bits>Long.SIZE){
					throw new ShouldNeverHappenError("Unable to build byte aligned block count for " + bits + " bits");
				}
			}
			
			//If the number count is small, try to pick a multiple of the count
			int optimizedBlockFillRepeats = 1;
			while(true){
				var newOptimizedBlockCount = optimizedBlockCount*(optimizedBlockFillRepeats + 1);
				if(newOptimizedBlockCount>8 || newOptimizedBlockCount*bits>Long.SIZE) break;
				optimizedBlockFillRepeats++;
			}
			optimizedBlockCount *= optimizedBlockFillRepeats;
			return optimizedBlockCount;
		}
		
		private static List<Range> buildRanges(char[] table){
			List<Range> ranges = new ArrayList<>();
			cLoop:
			for(char c : table){
				
				merging:
				while(ranges.size()>1){
					for(int beforeIndex = 0; beforeIndex<ranges.size(); beforeIndex++){
						var before = ranges.get(beforeIndex);
						
						for(int afterIndex = 0; afterIndex<ranges.size(); afterIndex++){
							if(afterIndex == beforeIndex) continue;
							var after = ranges.get(afterIndex);
							
							if(before.to == after.from){
								before.to = after.to;
								ranges.remove(afterIndex);
								continue merging;
							}
						}
					}
					break;
				}
				
				for(var range : ranges){
					if(range.from<=c && c<=range.to){
						continue cLoop;
					}
					if(range.from - 1 == c){
						range.from--;
						continue cLoop;
					}
					if(range.to + 1 == c){
						range.to++;
						continue cLoop;
					}
				}
				
				ranges.add(new Range(c, c));
			}
			
			if(DEBUG_VALIDATION){
				validateRanges(table, ranges);
			}
			return ranges;
		}
		
		private static void validateRanges(char[] table, List<Range> ranges){
			for(char c = Character.MIN_VALUE; c<Character.MAX_VALUE; c++){
				boolean oldR = false;
				for(char t : table){
					if(t == c){
						oldR = true;
						break;
					}
				}
				
				boolean newR = false;
				for(var range : ranges){
					if(range.from<=c && c<=range.to){
						newR = true;
						break;
					}
				}
				
				if(oldR != newR){
					throw new RuntimeException(c + "");
				}
			}
		}
		
		private int encode(char c)    { return indexTable[c - offset]; }
		private char decode(int index){ return chars[index]; }
		
		@Override
		public int calcSize(String str){
			return bitsToBytes(str.length()*bits);
		}
		@Override
		public float sizeWeight(){ return bits/8F; }
		@Override
		public String randomString(RandomGenerator random, int minLength, int maxLength){
			while(true){
				int    len  = maxLength == minLength? maxLength : random.nextInt(maxLength - minLength) + minLength;
				char[] buff = new char[len];
				for(int i = 0; i<buff.length; ){
					var  ri   = random.nextInt(ranges.length/2);
					char from = ranges[ri*2], to = ranges[ri*2 + 1];
					for(int l = Math.min(buff.length, i + random.nextInt(5) + 1); i<l; i++){
						char c = from == to? from : (char)(random.nextInt(to - from) + from);
						buff[i] = c;
					}
				}
				var res = new String(buff);
				if(Encoding.findBest(res).format == this){
					return res;
				}
			}
		}
		
		@Override
		public void write(ContentWriter w, String s) throws IOException{
			int i = 0, charCount = s.length();
			
			while(charCount - i>=blockCharCount){
				long acum  = 0;
				int  start = i;
				for(int j = 0; j<blockCharCount; j++){
					acum |= ((long)encode(s.charAt(start + j)))<<(bits*j);
				}
				w.writeWord(acum, blockBytes);
				i += blockCharCount;
			}
			
			var remainingChars = charCount - i;
			if(remainingChars == 0) return;
			
			var remainingBits = remainingChars*bits;
			if(remainingBits<64){
				long acum = 0;
				for(int j = 0; j<remainingChars; j++){
					acum |= ((long)encode(s.charAt(i + j)))<<(bits*j);
				}
				var bytes = bitsToBytes(remainingBits);
				var ones  = bytes*Byte.SIZE - remainingBits;
				if(ones>0){
					acum |= makeMask(ones)<<remainingBits;
				}
				w.writeWord(acum, bytes);
				return;
			}
			
			try(var stream = new BitOutputStream(w)){
				for(; i<charCount; i++){
					stream.writeBits(encode(s.charAt(i)), bits);
				}
			}
		}
		
		private static final int CHAR_ARRAY_OFFSET = UNSAFE.arrayBaseOffset(char[].class);
		
		@Override
		public void read(ContentInputStream src, CharBuffer dest) throws IOException{
			int charCount = dest.remaining();
			int i         = 0;
			if(charCount>=blockCharCount){
				if(dest.hasArray()){
					i = readBlocksDirect(src, dest, charCount);
				}else{
					i = readBlocks(src, dest, charCount);
				}
			}
			
			var remainingChars = charCount - i;
			if(remainingChars == 0) return;
			
			var remainingBits = remainingChars*bits;
			if(remainingBits<64){
				var  bytes = bitsToBytes(remainingBits);
				long data  = src.readWord(bytes);
				
				var buf  = new char[remainingChars];
				var mask = chars.length - 1;
				for(var j = 0; j<remainingChars; j++){
					buf[j] = chars[(int)((data>>(bits*j))&mask)];
				}
				dest.put(buf);
				
				var ones = bytes*Byte.SIZE - remainingBits;
				if(ones>0){
					int zeroIndex = BitUtils.findBinaryZero(data>>remainingBits, ones);
					if(zeroIndex != -1){
						throw new IllegalBitValue(zeroIndex + remainingBits);
					}
				}
				return;
			}
			
			try(var stream = new BitInputStream(src, remainingBits)){
				var buf = new char[Math.min(remainingChars, 32)];
				for(var j = 0; j<remainingChars; j++){
					buf[j] = chars[(int)stream.readBits(bits)];
				}
				dest.put(buf);
			}
		}
		private int readBlocks(ContentInputStream src, CharBuffer dest, int charCount) throws IOException{
			var mask = chars.length - 1;
			
			var buf = new char[blockCharCount];
			
			var blockCount = charCount/blockCharCount;
			
			for(int j = 0; j<blockCount; j++){
				var acum = src.readWord(blockBytes);
				
				for(int inner = 0; inner<blockCharCount; inner++){
					var idx = (acum>>(bits*inner))&mask;
					var c   = UNSAFE.getChar(chars, CHAR_ARRAY_OFFSET + (idx<<1));
					buf[inner] = c;
				}
				
				dest.put(buf);
			}
			
			return blockCount*blockCharCount;
		}
		private int readBlocksDirect(ContentInputStream src, CharBuffer dest, int charCount) throws IOException{
			var mask = chars.length - 1;
			
			var arr = dest.array();
			var off = CHAR_ARRAY_OFFSET + ((long)(dest.position() + dest.arrayOffset())<<1);
			
			var blockCount = charCount/blockCharCount;
			
			for(int j = 0; j<blockCount; j++){
				var acum = src.readWord(blockBytes);
				
				for(int inner = 0; inner<blockCharCount; inner++){
					var idx = (acum>>(bits*inner))&mask;
					var c   = UNSAFE.getChar(chars, CHAR_ARRAY_OFFSET + (idx<<1));
					UNSAFE.putChar(arr, off + inner*2L, c);
				}
				
				off += blockCharCount*2;
			}
			
			var i = blockCount*blockCharCount;
			dest.position(dest.position() + i);
			return i;
		}
		
		@Override
		public boolean canEncode(String s){
			for(int ci = 0, l = s.length(); ci<l; ci++){
				if(!validPoints.get(s.charAt(ci))){
					return false;
				}
			}
			return true;
		}
		
	}
	
	private static boolean isLatin1Compatible(String str){
		for(int i = 0, l = str.length(); i<l; i++){
			if(!isLatin1Compatible(str.charAt(i))){
				return false;
			}
		}
		return true;
	}
	private static boolean isLatin1Compatible(int c){
		return c<=255;
	}
	
	private static String generateByRange(Coding user, RandomGenerator random, int minLength, int maxLength, int maxVal){
		while(true){
			var    len  = maxLength == minLength? maxLength : random.nextInt(maxLength - minLength) + minLength;
			char[] buff = new char[len];
			for(int i = 0; i<len; i++){
				buff[i] = (char)(Math.pow(random.nextFloat(), 4)*maxVal);
			}
			var res = new String(buff);
			if(Encoding.findBest(res).format == user){
				return res;
			}
		}
	}
	
	
	public static final Encoding DEFAULT = LATIN1;
	
	private static final Encoding[] SORTED =
		Iters.from(Encoding.values()).sortedByD(c -> c.format.sizeWeight()).toArray(Encoding[]::new);
	
	public static Encoding findBest(String data){
		return switch(data.length()){
			case 0 -> DEFAULT;
			case 1 -> {
				var c = data.charAt(0);
				if(isLatin1Compatible(c)) yield LATIN1;
				if(!Character.isSurrogate(c)) yield UTF8;
				throw fail(data);
			}
			default -> {
				for(var encoding : SORTED){
					if(encoding.format.canEncode(data)){
						yield encoding;
					}
				}
				throw fail(data);
			}
		};
	}
	private static IllegalStateException fail(String data){
		return new IllegalStateException(Log.fmt("{#red\"{}\"#} is not a valid UTF-8 string", data));
	}
	
	public final Coding format;
	Encoding(Coding format){ this.format = format; }
	
	public int calcSize(String str){
		return format.calcSize(str);
	}
	public void write(ContentWriter dest, String str) throws IOException{
		format.write(dest, str);
	}
	public void read(ContentInputStream src, CharBuffer dest) throws IOException{
		format.read(src, dest);
	}
}
