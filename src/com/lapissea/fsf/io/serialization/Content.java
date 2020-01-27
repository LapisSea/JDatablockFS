package com.lapissea.fsf.io.serialization;

import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.flags.FlagReader;
import com.lapissea.fsf.flags.FlagWriter;
import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.ContentWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public interface Content<T>{
	
	Content<byte[]> BYTE_ARRAY=new Content<>(){
		
		@Override
		public void write(ContentWriter dest, byte[] bytes) throws IOException{
			var siz=NumberSize.bySize(bytes.length);
			
			var flags=new FlagWriter(NumberSize.BYTE);
			flags.writeEnum(siz);
			if(DEBUG_VALIDATION) flags.fillRestAllOne();
			
			flags.export(dest);
			
			siz.write(dest, bytes.length);
			dest.write(bytes);
		}
		
		@Override
		public byte[] read(ContentReader src) throws IOException{
			
			var flags=FlagReader.read(src, NumberSize.BYTE);
			
			var siz      =flags.readEnum(NumberSize.class);
			var arraySize=siz.read(src);
			
			if(DEBUG_VALIDATION){
				Assert(flags.checkRestAllOne());
			}
			
			var data=new byte[Math.toIntExact(arraySize)];
			
			src.readFully(data, 0, data.length);
			
			return data;
		}
		
		@Override
		public int length(byte[] dest){
			return NumberSize.BYTE.bytes+
			       NumberSize.bySize(dest.length).bytes+
			       dest.length;
		}
	};
	
	Content<long[]> LONG_ARRAY            =new Content<>(){
		@Override
		public void write(ContentWriter dest, long[] array) throws IOException{
			dest.writeInt4(array.length);
			for(long l : array){
				dest.writeInt8(l);
			}
		}
		
		@Override
		public long[] read(ContentReader src) throws IOException{
			long[] data=new long[src.readInt4()];
			for(int i=0;i<data.length;i++){
				data[i]=src.readInt8();
			}
			return data;
		}
		
		@Override
		public int length(long[] dest){
			return Integer.BYTES+dest.length*Long.BYTES;
		}
	};
	Content<String> NULL_TERMINATED_STRING=new Content<>(){
		@Override
		public void write(ContentWriter dest, String string) throws IOException{
			var bytes=string.getBytes(StandardCharsets.UTF_8);
			
			for(var b : bytes){
				if(b==0) throw new NullPointerException();
			}
			
			dest.write(bytes);
			dest.writeInt1(0);
		}
		
		@Override
		public String read(ContentReader src) throws IOException{
			StringBuilder result=new StringBuilder();
			
			int c;
			while((c=src.readInt1())!=0){
				if(DEBUG_VALIDATION&&c==-1){
					throw new RuntimeException();
				}
				result.append((char)c);
			}
			
			return result.toString();
		}
		
		@Override
		public int length(String dest){
			return (dest.length()+1)*Byte.BYTES;
		}
	};
	
	void write(ContentWriter dest, T t) throws IOException;
	
	T read(ContentReader src) throws IOException;
	
	int length(T dest);
	
}
