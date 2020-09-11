package com.lapissea.cfs.objects.text;

import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentWriter;
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
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static java.nio.charset.CodingErrorAction.*;
import static java.nio.charset.StandardCharsets.*;

class Encoding{
	
	private record UTF(CharsetEncoder utf8Enc, CharsetDecoder utf8Dec){
		private UTF(){
			this(UTF_8.newEncoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT),
			     UTF_8.newDecoder().onUnmappableCharacter(REPORT).onMalformedInput(REPORT));
		}
		
		private static final ThreadLocal<UTF> UTFS=ThreadLocal.withInitial(UTF::new);
		
		private static UTF get(){ return UTFS.get(); }
	}
	
	private record TableCoding(byte[] table, int offset, char[] chars, int bits){
		static TableCoding of(char... table){
			assert table.length<=Byte.MAX_VALUE;
			
			int bits=1;
			while(table.length>1<<bits) bits++;
			assert table.length==1<<bits;
			
			int min=IntStream.range(0, table.length).map(i->table[i]).min().orElseThrow();
			int max=IntStream.range(0, table.length).map(i->table[i]).max().orElse(-2)+1;
			
			byte[] tableIndex=new byte[max-min];
			Arrays.fill(tableIndex, (byte)-1);
			
			for(int i=0, j=table.length;i<j;i++){
				char c=table[i];
				tableIndex[c-min]=(byte)i;
			}
			return new TableCoding(tableIndex, min, table, bits);
		}
		
		int encode(char c)    { return table[c-offset]; }
		char decode(int index){ return chars[index]; }
		
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
		
		String read(ContentInputStream w, AbstractText text) throws IOException{
			int           mask=(1<<bits)-1;
			StringBuilder sb  =new StringBuilder(text.charCount());
			
			try(var stream=new BitInputStream(w)){
				for(int i=0;i<text.charCount();i++){
					stream.prepareBits(bits);
					int  index=stream.readBits(bits);
					char c    =decode(index);
					
					sb.append(c);
				}
			}
			return sb.toString();
		}
		boolean isCompatible(String s){
			return s.chars().allMatch(c->{
				for(char t : chars){
					if(t==c) return true;
				}
				return false;
			});
		}
		
	}
	
	enum CharEncoding{
		BASE_16(TableCoding.of(
			'A', 'B', 'C', 'D', 'E', 'F',
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9'
		)),
		BASE_64(TableCoding.of(
			'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
			'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
			'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', ' ', '/'
		)),
		ASCII(
			String::length,
			s->s.chars().allMatch(c->c<=0xFF),
			(w, text)->w.write(text.getBytes(US_ASCII)),
			(r, text)->new String(r.readInts1(text.charCount()), US_ASCII)),
		UTF8(
			CharEncoding::utf8Len,
			s->tryEncode(UTF.get().utf8Enc(), s),
			(w, s)->encode(UTF.get().utf8Enc(), s, w),
			(w, text)->decode(UTF.get().utf8Dec(), w, text));
		
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
		private static String decode(CharsetDecoder en, ContentInputStream in, AbstractText text) throws IOException{
			
			char[] str      =new char[text.charCount()];
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
		
		private final FunctionOI<String> calcSize;
		private final Predicate<String>  canEncode;
		
		private final UnsafeBiConsumer<ContentWriter, String, IOException>                    write;
		private final UnsafeBiFunction<ContentInputStream, AbstractText, String, IOException> read;
		
		CharEncoding(TableCoding coder){ this(coder::calcSize, coder::isCompatible, coder::write, coder::read); }
		CharEncoding(FunctionOI<String> calcSize,
		             Predicate<String> canEncode,
		             UnsafeBiConsumer<ContentWriter, String, IOException> write,
		             UnsafeBiFunction<ContentInputStream, AbstractText, String, IOException> read
		){
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
		public String read(ContentInputStream src, AbstractText dest) throws IOException{
			return read.apply(src, dest);
		}
	}
}
