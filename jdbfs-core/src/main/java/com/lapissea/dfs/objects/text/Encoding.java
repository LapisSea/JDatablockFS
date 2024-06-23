package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.exceptions.IllegalBitValue;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.util.PairM;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.io.bit.BitUtils.bitsToBytes;
import static com.lapissea.dfs.io.bit.BitUtils.makeMask;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;

class Encoding{
	
	public sealed interface Coding{
		void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException;
		void write(ContentWriter dest, String str) throws IOException;
		boolean canEncode(String str);
		int calcSize(String str);
		float sizeWeight();
	}
	
	private static final class UTF8Coding implements Coding{
		@Override
		public void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException{
			char[] buff      = new char[Math.min(1024, charCount)];
			int    remaining = charCount;
			
			try(Reader reader = new InputStreamReader(src, UTF_8_DEC)){
				while(remaining>0){
					int read = reader.read(buff, 0, Math.min(buff.length, remaining));
					if(read == -1) throw new EOFException();
					dest.append(buff, 0, read);
					remaining -= read;
				}
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
	}
	
	private static final class Latin1Coding implements Coding{
		@Override
		public void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException{
			var data = src.readInts1(charCount);
			dest.append(new CharSequence(){
				@Override
				public int length(){ return data.length; }
				@Override
				public char charAt(int index){ return (char)Byte.toUnsignedInt(data[index]); }
				@Override
				public CharSequence subSequence(int start, int end){ throw new UnsupportedOperationException(); }
			});
		}
		@Override
		public void write(ContentWriter dest, String str) throws IOException{
			dest.write(str.getBytes(ISO_8859_1));
		}
		@Override
		public boolean canEncode(String str){
			for(int i = 0, l = str.length(); i<l; i++){
				if(!latin1Compatible(str.charAt(i))){
					return false;
				}
			}
			return true;
		}
		@Override
		public int calcSize(String str){
			return str.length();
		}
		@Override
		public float sizeWeight(){ return 1; }
	}
	
	private static final class TableCoding implements Coding{
		private final byte[] indexTable;
		private final char[] chars, ranges;
		private final int offset, bits;
		private final int blockCharCount, blockBytes;
		
		private TableCoding(char... table){
			assert table.length<=Byte.MAX_VALUE;
			
			int bits = 1;
			while(table.length>1<<bits) bits++;
			assert table.length == (1<<bits);
			
			int offset = IntStream.range(0, table.length).map(i -> table[i]).min().orElseThrow();
			int end    = IntStream.range(0, table.length).map(i -> table[i]).max().orElse(-2) + 1;
			
			var indexTable = new byte[end - offset];
			Arrays.fill(indexTable, (byte)0xFF);
			
			for(int i = 0, j = table.length; i<j; i++){
				char c = table[i];
				indexTable[c - offset] = (byte)i;
			}
			var ranges = buildRanges(table);
			
			int blockCharCount = calcOptimizedBlockCount(bits);
			int blockBytes     = blockCharCount*bits/Byte.SIZE;
			
			this.indexTable = indexTable;
			this.offset = offset;
			this.chars = table.clone();
			this.bits = bits;
			this.ranges = ranges;
			this.blockCharCount = blockCharCount;
			this.blockBytes = blockBytes;
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
				if(newOptimizedBlockCount*bits>Long.SIZE) break;
				optimizedBlockFillRepeats++;
			}
			optimizedBlockCount *= optimizedBlockFillRepeats;
			return optimizedBlockCount;
		}
		
		private static char[] buildRanges(char[] table){
			List<PairM<Character, Character>> rangeBuilder = new ArrayList<>();
			cLoop:
			for(char c : table){
				
				merging:
				while(rangeBuilder.size()>1){
					for(int beforeIndex = 0; beforeIndex<rangeBuilder.size(); beforeIndex++){
						var before = rangeBuilder.get(beforeIndex);
						
						for(int afterIndex = 0; afterIndex<rangeBuilder.size(); afterIndex++){
							if(afterIndex == beforeIndex) continue;
							var after = rangeBuilder.get(afterIndex);
							
							if(before.obj2.equals(after.obj1)){
								before.obj2 = after.obj2;
								rangeBuilder.remove(afterIndex);
								continue merging;
							}
						}
					}
					break;
				}
				
				for(var range : rangeBuilder){
					var from = range.obj1;
					var to   = range.obj2;
					if(from<=c && c<=to){
						continue cLoop;
					}
					if(from - 1 == c){
						range.obj1--;
						continue cLoop;
					}
					if(to + 1 == c){
						range.obj2++;
						continue cLoop;
					}
				}
				
				rangeBuilder.add(new PairM<>(c, c));
			}
			
			char[] ranges = new char[rangeBuilder.size()*2];
			for(int i = 0; i<rangeBuilder.size(); i++){
				var r = rangeBuilder.get(i);
				ranges[i*2] = r.obj1;
				ranges[i*2 + 1] = r.obj2;
			}
			
			if(DEBUG_VALIDATION){
				validateRanges(table, ranges);
			}
			
			return ranges;
		}
		
		private static void validateRanges(char[] table, char[] ranges){
			for(char c = Character.MIN_VALUE; c<Character.MAX_VALUE; c++){
				
				boolean oldR = false;
				for(char t : table){
					if(t == c){
						oldR = true;
						break;
					}
				}
				
				boolean newR = false;
				for(int i = 0; i<ranges.length; i += 2){
					int from = ranges[i];
					int to   = ranges[i + 1];
					if(from<=c && c<=to){
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
		
		@Override
		public void read(ContentInputStream w, int charCount, StringBuilder sb) throws IOException{
			int i = 0;
			if(charCount>=blockCharCount){
				var mask = makeMask(bits);
				
				char[] dest = new char[blockCharCount];
				
				while(charCount - i>=blockCharCount){
					var acum = w.readWord(blockBytes);
					for(int j = 0; j<blockCharCount; j++){
						dest[j] = decode((int)((acum>>(bits*j))&mask));
					}
					sb.append(dest);
					i += blockCharCount;
				}
			}
			
			var remainingChars = charCount - i;
			if(remainingChars == 0) return;
			
			var remainingBits = remainingChars*bits;
			if(remainingBits<64){
				var  bytes = bitsToBytes(remainingBits);
				long data  = w.readWord(bytes);
				
				var buf  = new char[remainingChars];
				var mask = makeMask(bits);
				for(var j = 0; j<remainingChars; j++){
					buf[j] = decode((int)((data>>(bits*j))&mask));
				}
				sb.append(buf);
				
				var ones = bytes*Byte.SIZE - remainingBits;
				if(ones>0){
					int zeroIndex = BitUtils.findBinaryZero(data>>remainingBits, ones);
					if(zeroIndex != -1){
						throw new IllegalBitValue(zeroIndex + remainingBits);
					}
				}
				return;
			}
			
			try(var stream = new BitInputStream(w, remainingBits)){
				var buf = new char[Math.min(remainingChars, 32)];
				for(var j = 0; j<remainingChars; j++){
					buf[j] = decode((int)stream.readBits(bits));
				}
				sb.append(buf);
			}
		}
		@Override
		public boolean canEncode(String s){
			final var ranges = this.ranges;
			outer:
			for(int ci = 0, l = s.length(); ci<l; ci++){
				var c = s.charAt(ci);
				
				for(int i = 0; i<ranges.length; i += 2){
					int from = ranges[i];
					int to   = ranges[i + 1];
					if(from<=c && c<=to){
						continue outer;
					}
				}
				return false;
			}
			return true;
		}
		
	}
	
	private static final CharsetDecoder UTF_8_DEC = UTF_8.newDecoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT);
	
	private static boolean latin1Compatible(int c){
		return c<=255;
	}
	
	enum CharEncoding{
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
		
		public static final CharEncoding DEFAULT = LATIN1;
		
		private static final CharEncoding[] SORTED =
			Arrays.stream(CharEncoding.values()).sorted(Comparator.comparingDouble(c -> c.format.sizeWeight())).toArray(CharEncoding[]::new);
		public static CharEncoding findBest(String data){
			return switch(data.length()){
				case 0 -> DEFAULT;
				case 1 -> {
					var c = data.charAt(0);
					if(latin1Compatible(c)) yield LATIN1;
					if(!Character.isSurrogate(c)) yield UTF8;
					throw fail();
				}
				default -> {
					for(CharEncoding f : SORTED){
						if(f.canEncode(data)){
							yield f;
						}
					}
					throw fail();
				}
			};
		}
		private static RuntimeException fail(){
			throw new RuntimeException("Unable to encode string");
		}
		
		public final Coding format;
		CharEncoding(Coding format){ this.format = format; }
		
		public int calcSize(String str){
			return format.calcSize(str);
		}
		public boolean canEncode(String str){
			return format.canEncode(str);
		}
		public void write(ContentWriter dest, String str) throws IOException{
			format.write(dest, str);
		}
		public void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException{
			format.read(src, charCount, dest);
		}
	}
}
