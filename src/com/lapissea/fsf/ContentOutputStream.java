package com.lapissea.fsf;

import java.io.IOException;
import java.io.OutputStream;

public abstract class ContentOutputStream extends OutputStream{
	
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
		public void write(byte[] b, int off, int len) throws IOException{
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
	
	
	public final void writeBoolean(boolean v) throws IOException{
		write(v?1:0);
	}
	
	public final void writeByte(int v) throws IOException{
		write(v);
	}
	
	public final void writeShort(int v) throws IOException{
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	public final void writeChar(int v) throws IOException{
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	public final void writeInt(int v) throws IOException{
		write((v >>> 24)&0xFF);
		write((v >>> 16)&0xFF);
		write((v >>> 8)&0xFF);
		write((v >>> 0)&0xFF);
	}
	
	private byte writeBuffer[]=new byte[8];
	
	public final void writeLong(long v) throws IOException{
		writeBuffer[0]=(byte)(v >>> 56);
		writeBuffer[1]=(byte)(v >>> 48);
		writeBuffer[2]=(byte)(v >>> 40);
		writeBuffer[3]=(byte)(v >>> 32);
		writeBuffer[4]=(byte)(v >>> 24);
		writeBuffer[5]=(byte)(v >>> 16);
		writeBuffer[6]=(byte)(v >>> 8);
		writeBuffer[7]=(byte)(v >>> 0);
		write(writeBuffer, 0, 8);
	}
	
	public final void writeFloat(float v) throws IOException{
		writeInt(Float.floatToIntBits(v));
	}
	
	public final void writeDouble(double v) throws IOException{
		writeLong(Double.doubleToLongBits(v));
	}
}
