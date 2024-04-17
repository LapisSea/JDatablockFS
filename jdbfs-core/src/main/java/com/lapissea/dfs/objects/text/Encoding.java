package com.lapissea.dfs.objects.text;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.type.field.FieldNames;
import com.lapissea.util.PairM;
import com.lapissea.util.function.FunctionOI;
import com.lapissea.util.function.UnsafeBiConsumer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static java.nio.charset.CodingErrorAction.REPORT;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

class Encoding{
	
	private record TableCoding(byte[] indexTable, int offset, char[] chars, int bits, char[] ranges, int optimizedBlockCharCount,
	                           int optimizedBlockBytes){
		static TableCoding of(char... table){
			assert table.length<=Byte.MAX_VALUE;
			
			int bits = 1;
			while(table.length>1<<bits) bits++;
			assert table.length == (1<<bits);
			
			int min = IntStream.range(0, table.length).map(i -> table[i]).min().orElseThrow();
			int max = IntStream.range(0, table.length).map(i -> table[i]).max().orElse(-2) + 1;
			
			byte[] tableIndex = new byte[max - min];
			Arrays.fill(tableIndex, (byte)0xFF);
			
			for(int i = 0, j = table.length; i<j; i++){
				char c = table[i];
				tableIndex[c - min] = (byte)i;
			}
			var ranges = buildRanges(table);
			
			
			
			int optimizedBlockCount;
			if(ConfigDefs.TEXT_DISABLE_BLOCK_CODING.resolveVal()){
				optimizedBlockCount = -1;
			}else{
				optimizedBlockCount = 1;
				while((optimizedBlockCount*bits)%Byte.SIZE != 0){
					optimizedBlockCount++;
					if(optimizedBlockCount*bits>Long.SIZE){
						optimizedBlockCount = -1;
						break;
					}
				}
			}
			
			if(optimizedBlockCount != -1){
				int optimizedBlockFillRepeats = 1;
				while(true){
					var newOptimizedBlockCount = optimizedBlockCount*(optimizedBlockFillRepeats + 1);
					if(newOptimizedBlockCount*bits>Long.SIZE) break;
					optimizedBlockFillRepeats++;
				}
				optimizedBlockCount *= optimizedBlockFillRepeats;
			}
			
			int optimizedBlockBytes = optimizedBlockCount*bits/Byte.SIZE;
			
			
			return new TableCoding(tableIndex, min, table, bits, ranges, optimizedBlockCount, optimizedBlockBytes);
		}
		
		@SuppressWarnings("PointlessArithmeticExpression")
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
				ranges[i*2 + 0] = r.obj1;
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
		
		private int calcSize(String str){
			return calcSize(str.length());
		}
		private int calcSize(int len){
			return BitUtils.bitsToBytes(len*bits);
		}
		
		
		private void write(ContentWriter w, String s) throws IOException{
			int i = 0;
			
			if(optimizedBlockCharCount != -1 && s.length()>=optimizedBlockCharCount){
				byte[] buf = new byte[Long.BYTES];
				var    lb  = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
				
				while(s.length() - i>=optimizedBlockCharCount){
					long acum  = 0;
					int  start = i;
					for(int j = 0; j<optimizedBlockCharCount; j++){
						acum |= ((long)encode(s.charAt(start + j)))<<(bits*j);
					}
					lb.put(0, acum);
					w.write(buf, 0, optimizedBlockBytes);
					i += optimizedBlockCharCount;
				}
			}
			if(i<s.length()){
				try(var stream = new BitOutputStream(w)){
					for(; i<s.length(); i++){
						stream.writeBits(encode(s.charAt(i)), bits);
					}
				}
			}
		}
		
		private void read(ContentInputStream w, int charCount, StringBuilder sb) throws IOException{
			int i = 0;
			
			if(optimizedBlockCharCount != -1 && charCount>=optimizedBlockCharCount){
				byte[] buf  = new byte[Long.BYTES];
				var    lb   = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer();
				var    mask = BitUtils.makeMask(bits);
				
				char[] dest = new char[optimizedBlockCharCount];
				
				while(charCount - i>=optimizedBlockCharCount){
					w.readFully(buf, 0, optimizedBlockBytes);
					var acum = lb.get(0);
					for(int j = 0; j<optimizedBlockCharCount; j++){
						dest[j] = decode((int)((acum>>(bits*j))&mask));
					}
					sb.append(dest);
					i += optimizedBlockCharCount;
				}
			}
			if(i<charCount){
				try(var stream = new BitInputStream(w, charCount - i)){
					for(; i<charCount; i++){
						int  index = (int)stream.readBits(bits);
						char c     = decode(index);
						
						sb.append(c);
					}
				}
			}
		}
		private boolean isCompatible(String s){
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
	
	enum CharEncoding{
		BASE_16_U(TableCoding.of(
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
			'A', 'B', 'C', 'D', 'E', 'F'
		)),
		BASE_16_L(TableCoding.of(
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
			'a', 'b', 'c', 'd', 'e', 'f'
		)),
		BASE_32_U(TableCoding.of(
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			'0', '1', '2', '3', '4', '5'
		)),
		BASE_32_L(TableCoding.of(
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
			'0', '1', '2', '3', '4', '5'
		)),
		BASE_64(TableCoding.of(
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/'
		)),
		BASE_64_CNAME(TableCoding.of(
			//Variation of base 64 optimized for storing class names and fields.
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
			'0', '1', '2', '3', '4', '5', '_', '$', '[', ';', '.', FieldNames.GENERATED_FIELD_SEPARATOR
		)),
		ASCII(
			1, String::length,
			s -> s.chars().allMatch(c -> c<128),
			(w, text) -> w.write(text.getBytes(US_ASCII)),
			(r, charCount, dest) -> {
				var data = r.readInts1(charCount);
				dest.append(new CharSequence(){
					@Override
					public int length(){
						return data.length;
					}
					@Override
					public char charAt(int index){
						return (char)data[index];
					}
					@Override
					public CharSequence subSequence(int start, int end){
						throw new UnsupportedOperationException();
					}
				});
			}),
		UTF8(
			1.1F,
			CharEncoding::utf8Len,
			s -> UTF_8.newEncoder().canEncode(s),
			(w, s) -> {
				var en = UTF_8.newEncoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT);
				var b  = en.encode(CharBuffer.wrap(s));
				w.write(b.array(), 0, b.limit());
			},
			(w, charCount, dest) -> {
				char[] buff      = new char[Math.min(1024, charCount)];
				int    remaining = charCount;
				
				try(Reader reader = new InputStreamReader(w, UTF_8_DEC)){
					while(remaining>0){
						int read = reader.read(buff, 0, Math.min(buff.length, remaining));
						if(read == -1) throw new EOFException();
						dest.append(buff, 0, read);
						remaining -= read;
					}
				}
				
			});
		
		public static final CharEncoding DEFAULT = ASCII;
		
		private static final CharEncoding[] SORTED = Arrays.stream(CharEncoding.values()).sorted(Comparator.comparingDouble(c -> c.sizeWeight)).toArray(CharEncoding[]::new);
		public static CharEncoding findBest(String data){
			if(data.isEmpty()){
				return DEFAULT;
			}
			if(data.length() == 1){
				//Can't do better than 1 byte. Prefer ASCII if possible because ASCII fast as fuck boi
				if(ASCII.canEncode(data)){
					return ASCII;
				}
			}
			for(CharEncoding f : SORTED){
				if(f.canEncode(data)){
					return f;
				}
			}
			throw new RuntimeException("Unable to encode \"" + data + '"');
		}
		
		private static int utf8Len(String s){
			int count = 0;
			for(int i = 0, len = s.length(); i<len; i++){
				char ch = s.charAt(i);
				if(ch<=0x7F) count++;
				else if(ch<=0x7FF) count += 2;
				else if(Character.isHighSurrogate(ch)){
					count += 4;
					++i;
				}else count += 3;
				
			}
			return count;
		}
		
		private interface Read{
			void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException;
		}
		
		public final float sizeWeight;
		
		private final FunctionOI<String> calcSize;
		private final Predicate<String>  canEncode;
		
		private final UnsafeBiConsumer<ContentWriter, String, IOException> write;
		private final Read                                                 read;
		
		CharEncoding(TableCoding coder){ this(coder.bits/8F, coder::calcSize, coder::isCompatible, coder::write, coder::read); }
		CharEncoding(float sizeWeight, FunctionOI<String> calcSize,
		             Predicate<String> canEncode,
		             UnsafeBiConsumer<ContentWriter, String, IOException> write,
		             Read read
		){
			this.sizeWeight = sizeWeight;
			this.calcSize = calcSize;
			this.canEncode = canEncode;
			this.write = write;
			this.read = read;
		}
		
		public int calcSize(String str){
			return calcSize.apply(str);
		}
		public boolean canEncode(String str){
			return canEncode.test(str);
		}
		public void write(ContentWriter dest, String str) throws IOException{
			write.accept(dest, str);
		}
		public void read(ContentInputStream src, int charCount, StringBuilder dest) throws IOException{
			read.read(src, charCount, dest);
		}
	}
}
