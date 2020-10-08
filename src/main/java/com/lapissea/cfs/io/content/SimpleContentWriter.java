package com.lapissea.cfs.io.content;

import com.lapissea.util.function.UnsafeLongConsumer;

import java.io.IOException;
import java.io.OutputStream;

public abstract class SimpleContentWriter implements ContentWriter{
	
	public interface WriteChunked{
		void write(byte[] b, int off, int len) throws IOException;
	}
	
	public static ContentWriter pass(OutputStream target){
		return new SimpleContentWriter(){
			@Override
			public void write(int b) throws IOException{
				target.write(b);
			}
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				target.write(b, off, len);
			}
		};
	}
	
	public static ContentWriter single(UnsafeLongConsumer<IOException> write){
		return new SimpleContentWriter(){
			@Override
			public void write(int b) throws IOException{
				write.accept(b);
			}
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				for(int i=0;i<len;i++){
					write(b[off+i]);
				}
			}
		};
	}
	
	public static ContentWriter chunked(WriteChunked write){
		return new SimpleContentWriter(){
			@Override
			public void write(int b) throws IOException{
				write.write(new byte[]{(byte)b}, 0, 1);
			}
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				write.write(b, off, len);
			}
		};
	}
}
