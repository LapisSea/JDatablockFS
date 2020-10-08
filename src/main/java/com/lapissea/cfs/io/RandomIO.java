package com.lapissea.cfs.io;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentInputStream;
import com.lapissea.cfs.io.content.ContentOutputStream;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.streams.RandomInputStream;
import com.lapissea.cfs.io.streams.RandomOutputStream;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.objects.chunk.ChunkPointer;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.awt.*;
import java.io.Flushable;
import java.io.IOException;
import java.util.Objects;

public interface RandomIO extends Flushable, ContentWriter, ContentReader, Sizable.Mod{
	
	enum Mode{
		READ_ONLY(true, false),
		READ_WRITE(true, true);
		
		public final boolean canRead;
		public final boolean canWrite;
		
		Mode(boolean canRead, boolean canWrite){
			this.canRead=canRead;
			this.canWrite=canWrite;
		}
	}
	
	interface Creator{
		
		default String hexdump() throws IOException{
			return hexdump(32);
		}
		
		default String hexdump(int maxWidth) throws IOException{
			return hexdump(TextUtil.toNamedJson(this), maxWidth);
		}
		
		default String hexdump(String title) throws IOException{
			return hexdump(title, 32);
		}
		
		default String hexdump(String title, int maxWidth) throws IOException{
			return io().hexdump(title, maxWidth);
		}
		
		default void ioAt(ChunkPointer ptr, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			ioAt(ptr.getValue(), session);
		}
		
		default <T> T ioAt(ChunkPointer ptr, UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			return ioAt(ptr.getValue(), session);
		}
		
		default void ioAt(long offset, UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			try(var io=ioAt(offset)){
				session.accept(io);
			}
		}
		
		default <T> T ioAt(long offset, UnsafeFunction<RandomIO, T, IOException> session) throws IOException{
			try(var io=ioAt(offset)){
				return session.apply(io);
			}
		}
		
		default RandomIO ioAt(long offset) throws IOException{
			return io().setPos(offset);
		}
		
		default <T extends IOInstance> T storeTo(Cluster cluster, T dest) throws IOException{
			io(io->dest.readStruct(cluster, io));
			return dest;
		}
		
		default void io(UnsafeConsumer<RandomIO, IOException> session) throws IOException{
			try(var io=io()){
				session.accept(io);
			}
		}
		
		RandomIO io() throws IOException;
		
	}
	
	long getPos() throws IOException;
	RandomIO setPos(long pos) throws IOException;
	
	default RandomIO setPos(INumber pos) throws IOException{
		return setPos(pos.getValue());
	}
	
	long getCapacity() throws IOException;
	RandomIO setCapacity(long newCapacity) throws IOException;
	
	default RandomIO ensureCapacity(long capacity) throws IOException{
		if(getCapacity()<capacity){
			setCapacity(capacity);
		}
		return this;
	}
	
	@Override
	void close() throws IOException;
	
	@Override
	void flush() throws IOException;
	
	default void trim() throws IOException{
		var pos =getPos();
		var size=getSize();
		if(pos>=size) return;
		setCapacity(pos);
	}
	
	@Override
	default long skip(long n) throws IOException{
		if(n==0) return 0;
		long toSkip=Math.min(n, remaining());
		setPos(getPos()+toSkip);
		return toSkip;
	}
	
	default long remaining() throws IOException{
		long siz=getSize();
		long pos=getPos();
		return siz-pos;
	}
	
	////////
	
	
	@Override
	int read() throws IOException;
	
	@Override
	default int read(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		int i=off;
		for(int j=off+len;i<j;i++){
			var bi=read();
			if(bi<0) break;
			b[i]=(byte)bi;
		}
		return i-off;
	}
	
	
	////////
	
	
	@Override
	void write(int b) throws IOException;
	
	@Override
	default void write(byte[] b, int off, int len) throws IOException{
		Objects.checkFromIndexSize(off, len, b.length);
		for(int i=off, j=off+len;i<j;i++){
			write(b[i]);
		}
	}
	
	/**
	 * Simiar to the write methods except it writes some number of 0 bytes but does not modify things such as the size of the data. (useful for clearing garbage data after some data has ben shrunk)
	 */
	void fillZero(long requestedMemory) throws IOException;
	
	default ObjectPointer<?> getGlobalRef() throws IOException{
		throw new UnsupportedOperationException();
	}
	long getGlobalPos() throws IOException;
	
	default RandomIO readOnly(){
		return new RandomIOReadOnly(this);
	}
	
	@Override
	default ContentInputStream inStream(){
		return new RandomInputStream(this);
	}
	
	@Override
	default ContentOutputStream outStream(){ return outStream(true); }
	default ContentOutputStream outStream(boolean trimOnClose){ return new RandomOutputStream(this, trimOnClose); }
	
	default ChunkPointer posAsPtr() throws IOException{
		return new ChunkPointer(getPos());
	}
	
	default String hexdump() throws IOException{
		return hexdump(TextUtil.toNamedJson(this), 32);
	}
	
	default String hexdump(String title, int maxWidth) throws IOException{
		
		long size=getSize();
		
		int    digits=String.valueOf(size).length();
		String format="%0"+digits+"d/%0"+digits+"d: ";
		
		StringBuilder result=new StringBuilder(128).append("hexdump: ").append(title).append("\n");
		
		int round=8;
		int space=result.length()-format.formatted(0, 0).length();
		int width=Math.min(Math.max((int)Math.round((Math.max(space/4.0, Math.sqrt(size))/(double)round)), 1)*round, maxWidth);
		
		Font font=new Font("SERIF", Font.PLAIN, 1);
		
		try(ContentReader in=this){
			
			long   read   =0;
			long   lineNum=0;
			byte[] line   =new byte[width];
			
			while(true){
				long remaining=size-read;
				if(remaining==0) return result.toString();
				
				int lineSiz=(int)Math.min(remaining, line.length);
				
				result.append(format.formatted(read, read+lineSiz));
				
				read+=lineSiz;
				in.readFully(line, 0, lineSiz);
				
				
				for(int i=0;i<line.length;i++){
					if(i>=lineSiz){
						result.append("  ");
					}else{
						var ay=Integer.toHexString(line[i]&0xFF).toUpperCase();
						if(ay.length()==1) result.append('0');
						result.append(ay);
					}
					result.append(' ');
				}
				
				for(int i=0;i<lineSiz;i++){
					char c=(char)line[i];
					
					result.append((char)switch(c){
						case 0 -> '␀';
						case '\n' -> '↵';
						case '\t' -> '↹';
//						case ' ' -> '⌴';
						default -> font.canDisplay(c)?c:'·';
					});
				}
				if(read<size) result.append('\n');
			}
			
		}
	}
}
