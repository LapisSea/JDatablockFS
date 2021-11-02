package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.PairM;
import com.lapissea.util.function.FunctionOI;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeBiFunction;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static com.lapissea.cfs.GlobalConfig.*;
import static java.nio.charset.CodingErrorAction.*;
import static java.nio.charset.StandardCharsets.*;

class Encoding{
	
	private record UTF(CharsetEncoder utf8Enc, CharsetDecoder utf8Dec){
		private UTF(){
			this(UTF_8.newEncoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT),
			     UTF_8.newDecoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT));
		}
		
		private static final ThreadLocal<UTF> UTFS=ThreadLocal.withInitial(UTF::new);
		
		private static UTF get(){return UTFS.get();}
	}
	
	private record TableCoding(byte[] table, int offset, char[] chars, int bits, char[] ranges){
		static TableCoding of(char... table){
			assert table.length<=Byte.MAX_VALUE;
			
			int bits=1;
			while(table.length>1<<bits) bits++;
			assert table.length==(1<<bits);
			
			int min=IntStream.range(0, table.length).map(i->table[i]).min().orElseThrow();
			int max=IntStream.range(0, table.length).map(i->table[i]).max().orElse(-2)+1;
			
			byte[] tableIndex=new byte[max-min];
			Arrays.fill(tableIndex, (byte)-1);
			
			for(int i=0, j=table.length;i<j;i++){
				char c=table[i];
				tableIndex[c-min]=(byte)i;
			}
			
			var ranges=buildRanges(table);
			
			return new TableCoding(tableIndex, min, table, bits, ranges);
		}
		
		@SuppressWarnings("PointlessArithmeticExpression")
		private static char[] buildRanges(char[] table){
			List<PairM<Character, Character>> rangeBuilder=new ArrayList<>();
			cLoop:
			for(char c : table){
				
				merging:
				while(rangeBuilder.size()>1){
					for(int beforeIndex=0;beforeIndex<rangeBuilder.size();beforeIndex++){
						var before=rangeBuilder.get(beforeIndex);
						
						for(int afterIndex=0;afterIndex<rangeBuilder.size();afterIndex++){
							if(afterIndex==beforeIndex) continue;
							var after=rangeBuilder.get(afterIndex);
							
							if(before.obj2.equals(after.obj1)){
								before.obj2=after.obj2;
								rangeBuilder.remove(afterIndex);
								continue merging;
							}
						}
					}
					break;
				}
				
				for(var range : rangeBuilder){
					var from=range.obj1;
					var to  =range.obj2;
					if(from<=c&&c<=to){
						continue cLoop;
					}
					if(from-1==c){
						range.obj1--;
						continue cLoop;
					}
					if(to+1==c){
						range.obj2++;
						continue cLoop;
					}
				}
				
				rangeBuilder.add(new PairM<>(c, c));
			}
			
			char[] ranges=new char[rangeBuilder.size()*2];
			for(int i=0;i<rangeBuilder.size();i++){
				var r=rangeBuilder.get(i);
				ranges[i*2+0]=r.obj1;
				ranges[i*2+1]=r.obj2;
			}
			
			if(DEBUG_VALIDATION){
				validateRanges(table, ranges);
			}
			
			return ranges;
		}
		
		private static void validateRanges(char[] table, char[] ranges){
			for(char c=Character.MIN_VALUE;c<Character.MAX_VALUE;c++){
				
				boolean oldR=false;
				for(char t : table){
					if(t==c){
						oldR=true;
						break;
					}
				}
				
				boolean newR=false;
				for(int i=0;i<ranges.length;i+=2){
					int from=ranges[i];
					int to  =ranges[i+1];
					if(from<=c&&c<=to){
						newR=true;
						break;
					}
				}
				
				if(oldR!=newR){
					throw new RuntimeException(c+"");
				}
			}
		}
		
		int encode(char c)    {return table[c-offset];}
		char decode(int index){return chars[index];}
		
		int calcSize(String str){
			return calcSize(str.length());
		}
		int calcSize(int len){
			return (int)Math.ceil(len*(double)bits/8D);
		}
		
		
		void write(ContentWriter w, String s) throws IOException{
			try(var stream=new BitOutputStream(w)){
				for(int i=0;i<s.length();i++){
					char c    =s.charAt(i);
					int  index=encode(c);
					
					stream.writeBits(index, bits);
				}
			}
		}
		
		String read(ContentInputStream w, int charCount) throws IOException{
			StringBuilder sb=new StringBuilder(charCount);
			
			try(var stream=new BitInputStream(w)){
				for(int i=0;i<charCount;i++){
					int  index=(int)stream.readBits(bits);
					char c    =decode(index);
					
					sb.append(c);
				}
			}
			return sb.toString();
		}
		boolean isCompatible(String s){
			return s.chars().allMatch(c->{
				for(int i=0;i<ranges.length;i+=2){
					int from=ranges[i];
					int to  =ranges[i+1];
					if(from<=c&&c<=to){
						return true;
					}
				}
				return false;
			});
		}
		
	}
	
	enum CharEncoding{
		BASE_16(0.5F, TableCoding.of(
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
			'A', 'B', 'C', 'D', 'E', 'F'
		)),
		BASE_64(0.66F, TableCoding.of(
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ' ', '.'
		)),
		ASCII(
			1, String::length,
			s->s.chars().allMatch(c->c<=0xFF),
			(w, text)->w.write(text.getBytes(US_ASCII)),
			(r, charCount)->new String(r.readInts1(charCount), US_ASCII)),
		UTF8(
			1.1F, CharEncoding::utf8Len,
			s->tryEncode(UTF.get().utf8Enc(), s),
			(w, s)->encode(UTF.get().utf8Enc(), s, w),
			(w, charCount)->decode(UTF.get().utf8Dec(), w, charCount));
		
		private static final CharEncoding[] SORTED=Arrays.stream(CharEncoding.values()).sorted(Comparator.comparingDouble(c->c.sizeWeight)).toArray(CharEncoding[]::new);
		public static CharEncoding findBest(String data){
			if(data.isEmpty()){
				return CharEncoding.ASCII;
			}
			for(CharEncoding f : SORTED){
				if(f.canEncode(data)){
					return f;
				}
			}
			throw new RuntimeException("Unable to encode \""+data+'"');
		}
		
		private static int utf8Len(String s){
			int count=0;
			for(int i=0, len=s.length();i<len;i++){
				char ch=s.charAt(i);
				if(ch<=0x7F) count++;
				else if(ch<=0x7FF) count+=2;
				else if(Character.isHighSurrogate(ch)){
					count+=4;
					++i;
				}else count+=3;
				
			}
			return count;
		}
		
		
		private static ByteBuffer encode(CharsetEncoder en, String s){
			try{
				return en.encode(CharBuffer.wrap(s));
			}catch(CharacterCodingException e){
				throw new RuntimeException(e);
			}
		}
		private static String decode(CharsetDecoder en, ContentInputStream in, int charCount) throws IOException{
			
			char[] str      =new char[charCount];
			int    remaining=str.length;
			int    off      =0;
			
			try(Reader reader=new InputStreamReader(in, en)){
				while(remaining>0){
					int read=reader.read(str, off, remaining);
					if(read==-1) throw new EOFException();
					remaining-=read;
					off+=read;
				}
			}
			
			return new String(str);
		}
		
		private static void encode(CharsetEncoder en, String s, ContentWriter w) throws IOException{
			var b=encode(en, s);
			w.write(b.array(), 0, b.limit());
		}
		
		private static boolean tryEncode(CharsetEncoder en, String s){
			try{
				en.encode(CharBuffer.wrap(s));
				return true;
			}catch(CharacterCodingException e){
				return false;
			}
		}
		
		public final float sizeWeight;
		
		private final FunctionOI<String> calcSize;
		private final Predicate<String>  canEncode;
		
		private final UnsafeBiConsumer<ContentWriter, String, IOException>               write;
		private final UnsafeBiFunction<ContentInputStream, Integer, String, IOException> read;
		
		CharEncoding(float sizeWeight, TableCoding coder){this(sizeWeight, coder::calcSize, coder::isCompatible, coder::write, coder::read);}
		CharEncoding(float sizeWeight, FunctionOI<String> calcSize,
		             Predicate<String> canEncode,
		             UnsafeBiConsumer<ContentWriter, String, IOException> write,
		             UnsafeBiFunction<ContentInputStream, Integer, String, IOException> read
		){
			this.sizeWeight=sizeWeight;
			this.calcSize=calcSize;
			this.canEncode=canEncode;
			this.write=write;
			this.read=read;
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
		public String read(ContentInputStream src, int charCount) throws IOException{
			return read.apply(src, charCount);
		}
	}
}
