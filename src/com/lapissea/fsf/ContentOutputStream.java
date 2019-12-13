package com.lapissea.fsf;

import com.lapissea.util.NotNull;

import java.io.IOException;
import java.io.OutputStream;

public abstract class ContentOutputStream extends OutputStream implements ContentWriter{
	
	public static class Wrapp extends ContentOutputStream{
		
		private final OutputStream os;
		
		public Wrapp(OutputStream os){
			this.os=os;
		}
		
		@Override
		public void write(int b) throws IOException{
			os.write(b);
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			os.write(b, off, len);
		}
		
		@Override
		public void flush() throws IOException{
			os.flush();
		}
		
		@Override
		public void close() throws IOException{
			os.close();
		}
		
	}
	
}
