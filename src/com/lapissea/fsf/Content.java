package com.lapissea.fsf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.lapissea.fsf.FileSystemInFile.*;

public interface Content<T>{
	
	Content<long[]> LONG_ARRAY            =new Content<>(){
		@Override
		public void write(ContentOutputStream dest, long[] array) throws IOException{
			dest.writeInt4(array.length);
			for(long l : array){
				dest.writeInt8(l);
			}
		}
		
		@Override
		public long[] read(ContentInputStream src) throws IOException{
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
		public void write(ContentOutputStream dest, String string) throws IOException{
			var bytes=string.getBytes(StandardCharsets.UTF_8);
			
			for(var b : bytes){
				if(b==0) throw new NullPointerException();
			}
			
			dest.write(bytes);
			dest.writeInt1(0);
		}
		
		@Override
		public String read(ContentInputStream src) throws IOException{
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
	
	void write(ContentOutputStream dest, T t) throws IOException;
	
	T read(ContentInputStream src) throws IOException;
	
	int length(T dest);
	
}
