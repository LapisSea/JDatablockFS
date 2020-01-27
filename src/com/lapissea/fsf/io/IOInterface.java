package com.lapissea.fsf.io;

import com.lapissea.fsf.Utils;
import com.lapissea.util.NotNull;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.stream.LongStream;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.util.UtilL.*;

public interface IOInterface{
	
	/**
	 * <p>Creates a new random read and write interface.</p>
	 * <p>Writing <b>will not</b> implicitly truncate the underlying contents when closed.</p>
	 *
	 * @return RandomIO instance
	 */
	RandomIO doRandom() throws IOException;
	
	long getSize() throws IOException;
	
	/**
	 * Tries to grows or shrinks capacity as closely as it is convenient for the underlying data. <br>
	 * <br>
	 * If growing, it is required that capacity is set to greater or equal to newCapacity.<br>
	 * If shrinking, it is not required that capacity is shrunk but is required to always be greater or equal to newCapacity.
	 */
	void setCapacity(long newCapacity) throws IOException;
	
	long getCapacity() throws IOException;
	
	
	class MemoryRA implements IOInterface{
		public transient UnsafeConsumer<long[], IOException> onWrite;
		
		private byte[] bb;
		private int    used;
		
		public MemoryRA(IOInterface data) throws IOException{
			bb=data.readAll();
			used=bb.length;
		}
		
		public MemoryRA(){
			bb=new byte[4];
		}
		
		@Override
		public RandomIO doRandom(){
			return new RandomIO(){
				
				private final byte[] buf=new byte[8];
				private int pos;
				
				@Override
				public RandomIO setPos(long pos){
					if(pos<0) throw new IndexOutOfBoundsException();
					this.pos=Math.toIntExact(pos);
					return this;
				}
				
				@Override
				public long getPos(){
					return Math.min(pos, used);
				}
				
				@Override
				public long getSize(){
					return MemoryRA.this.getSize();
				}
				
				@Override
				public long getCapacity(){
					return MemoryRA.this.getCapacity();
				}
				
				@Override
				public RandomIO setCapacity(long newCapacity){
					MemoryRA.this.setCapacity(newCapacity);
					pos=Math.min(pos, used);
					return this;
				}
				
				@Override
				public void close(){}
				
				@Override
				public void flush(){}
				
				@Override
				public int read(){
					int remaining=(int)(getSize()-getPos());
					if(remaining<=0) return -1;
					return bb[pos++]&0xFF;
				}
				
				@Override
				public int read(byte[] b, int off, int len){
					int remaining=(int)(getSize()-getPos());
					if(remaining<=0) return -1;
					if(remaining<len) len=remaining;
					
					System.arraycopy(bb, pos, b, off, len);
					pos+=len;
					return len;
				}
				
				@Override
				public void write(int b){
					int remaining=(int)(getCapacity()-getPos());
					if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity()+1, getCapacity()+1-remaining)));
					bb[pos]=(byte)b;
					if(onWrite!=null){
						try{
							onWrite.accept(new long[]{pos});
						}catch(Throwable ignored){}
					}
					pos++;
					if(used<pos) used=pos;
				}
				
				@Override
				public void write(byte[] b, int off, int len){
					write(b, off, len, true);
				}
				
				public void write(byte[] b, int off, int len, boolean pushPos){
					int remaining=(int)(getCapacity()-getPos());
					if(remaining<len) setCapacity(Math.max(4, Math.max(getCapacity()<<1, getCapacity()+len-remaining)));
					
					System.arraycopy(b, off, bb, pos, len);
					if(onWrite!=null){
						try{
							onWrite.accept(LongStream.range(pos, pos+len).toArray());
						}catch(Throwable ignored){}
					}
					if(pushPos){
						pos+=len;
						if(used<pos) used=pos;
					}
				}
				
				@Override
				public void fillZero(long requestedMemory) throws IOException{
					Utils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
				}
				
				@Override
				public long getGlobalPos(){
					return getPos();
				}
				
				@Override
				public byte[] contentBuf(){
					return buf;
				}
				
			};
		}
		
		@Override
		public long getSize(){
			return used;
		}
		
		@Override
		public long getCapacity(){
			return bb.length;
		}
		
		@Override
		public void setCapacity(long newCapacity){
			if(getCapacity()==newCapacity) return;
			bb=Arrays.copyOf(bb, (int)newCapacity);
			used=Math.min(used, bb.length);
		}
	}
	
	class FileRA implements IOInterface{
		public UnsafeConsumer<long[], IOException> onWrite;
		
		private       RandomAccessFile ra;
		private final String           path;
		
		@Override
		public String toString(){
			return path;
		}
		
		public FileRA(File file, Config config) throws IOException{
			File enforcedFile=config.shouldEnforceExtension?config.enforceExtension(file):file;
			
			if(!enforcedFile.isFile()){
				enforcedFile=config.enforceExtension(enforcedFile);
				
				//noinspection ResultOfMethodCallIgnored
				enforcedFile.createNewFile();
			}
			
			path=enforcedFile.getPath();
			ra=new RandomAccessFile(enforcedFile, "rw");
		}
		
		@Override
		public RandomIO doRandom(){
			return new RandomIO(){
				private long pos;
				
				private final byte[] buf=new byte[8];
				
				@Override
				public byte[] contentBuf(){
					return buf;
				}
				
				@Override
				public RandomIO setPos(long pos){
					if(pos<0) throw new IndexOutOfBoundsException();
					this.pos=pos;
					return this;
				}
				
				@Override
				public long getPos() throws IOException{
					return Math.min(this.pos, getSize());
				}
				
				@Override
				public long getSize() throws IOException{
					return ra.length();
				}
				
				@Override
				public long getCapacity() throws IOException{
					return ra.length();
				}
				
				@Override
				public RandomIO setCapacity(long newCapacity) throws IOException{
					ra.setLength(newCapacity);
					return this;
				}
				
				@Override
				public void close(){
//					ra.close();
				}
				
				@Override
				public void flush(){ }
				
				private void snapPos() throws IOException{
					var pos=getPos();
					var p0 =ra.getFilePointer();
					if(p0!=pos) ra.seek(pos);
					var p1=ra.getFilePointer();
					Assert(pos==p1);
				}
				
				@Override
				public int read() throws IOException{
					snapPos();
					var i=ra.read();
					if(i!=-1) pos++;
					return i;
				}
				
				
				@Override
				public int read(byte[] b, int off, int len) throws IOException{
					snapPos();
					var read=ra.read(b, off, len);
					if(read>0) pos+=read;
					return read;
				}
				
				@Override
				public void write(int b) throws IOException{
					snapPos();
					ra.write(b);
					if(onWrite!=null){
						try{
							onWrite.accept(new long[]{pos});
						}catch(Throwable ignored){}
					}
					pos++;
				}
				
				@Override
				public void write(byte[] b, int off, int len) throws IOException{
					write(b, off, len, true);
				}
				
				public void write(byte[] b, int off, int len, boolean pushPos) throws IOException{
					snapPos();
					ra.write(b, off, len);
					if(onWrite!=null){
						try{
							onWrite.accept(LongStream.range(pos, pos+len).toArray());
						}catch(Throwable ignored){}
					}
					if(pushPos) pos+=len;
				}
				
				@Override
				public void fillZero(long requestedMemory) throws IOException{
					Utils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
				}
				
				@Override
				public long getGlobalPos() throws IOException{
					return getPos();
				}
			};
		}
		
		@Override
		public long getSize() throws IOException{
			return ra.length();
		}
		
		@Override
		public long getCapacity() throws IOException{
			return ra.length();
		}
		
		@Override
		public void setCapacity(long newCapacity) throws IOException{
			ra.setLength(newCapacity);
		}
	}
	
	
	class RandomInputStream extends ContentInputStream{
		
		private final byte[]   buf=new byte[8];
		private final RandomIO io;
		private       Long     mark;
		
		RandomInputStream(RandomIO io){this.io=io;}
		
		@Override
		public int read(@NotNull byte[] b, int off, int len) throws IOException{
			return io.read(b, off, len);
		}
		
		@Override
		public int read() throws IOException{
			return io.read();
		}
		
		@Override
		public long skip(long n) throws IOException{
			return io.skip(n);
		}
		
		@Override
		public void close() throws IOException{
			io.close();
		}
		
		@SuppressWarnings("AutoBoxing")
		@Override
		public synchronized void mark(int readLimit){
			try{
				mark=io.getPos();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		@Override
		public synchronized void reset() throws IOException{
			io.setPos(mark);
		}
		
		@Override
		public boolean markSupported(){
			return true;
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+"{"+io+'}';
		}
		
		@Override
		public byte[] contentBuf(){
			return buf;
		}
		
		@Override
		public long getOffset() throws IOException{
			return io.getPos();
		}
		
		@Override
		public long getGlobalOffset() throws IOException{
			return io.getGlobalPos();
		}
	}
	
	class RandomOutputStream extends ContentOutputStream{
		
		private final RandomIO io;
		private final boolean  trimOnClose;
		
		RandomOutputStream(RandomIO io, boolean trimOnClose){
			this.io=io;
			this.trimOnClose=trimOnClose;
		}
		
		@Override
		public void write(@NotNull byte[] b, int off, int len) throws IOException{
			io.write(b, off, len);
		}
		
		@Override
		public void flush() throws IOException{
			io.flush();
		}
		
		@Override
		public void close() throws IOException{
			if(trimOnClose){
				io.trim();
			}
			io.close();
		}
		
		@Override
		public void write(int b) throws IOException{
			io.write(b);
		}
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+'{'+io+'}';
		}
	}
	
	default ContentOutputStream write(boolean trimOnClose) throws IOException{ return write(0, trimOnClose); }
	
	default ContentOutputStream write(long fileOffset, boolean trimOnClose) throws IOException{
		return new RandomOutputStream(doRandom().setPos(fileOffset), trimOnClose);
	}
	
	default void write(boolean trimOnClose, byte[] data) throws IOException                 { write(0, trimOnClose, data); }
	
	default void write(long fileOffset, boolean trimOnClose, byte[] data) throws IOException{ write(fileOffset, trimOnClose, data.length, data); }
	
	default void write(long fileOffset, boolean trimOnClose, int length, byte[] data) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.write(data, 0, length);
			if(trimOnClose) io.trim();
		}
	}
	
	
	default <T> T read(UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{ return read(0, reader); }
	
	default <T> T read(long fileOffset, UnsafeFunction<ContentInputStream, T, IOException> reader) throws IOException{
		try(var io=read(fileOffset)){
			return reader.apply(io);
		}
	}
	
	default void read(UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{ read(0, reader); }
	
	default void read(long fileOffset, UnsafeConsumer<ContentInputStream, IOException> reader) throws IOException{
		try(var io=read(fileOffset)){
			reader.accept(io);
		}
	}
	
	default ContentInputStream read() throws IOException{ return read(0); }
	
	default ContentInputStream read(long fileOffset) throws IOException{
		return new RandomInputStream(doRandom().setPos(fileOffset));
	}
	
	
	default byte[] read(long fileOffset, int length) throws IOException{
		byte[] dest=new byte[length];
		read(fileOffset, dest);
		return dest;
	}
	
	default void read(long fileOffset, byte[] dest) throws IOException{
		read(fileOffset, dest.length, dest);
	}
	
	default void read(long fileOffset, int length, byte[] dest) throws IOException{
		try(var io=doRandom()){
			io.setPos(fileOffset);
			io.readFully(dest, 0, length);
		}
	}
	
	default byte[] readAll() throws IOException{
		try(var stream=read()){
			byte[] data=new byte[Math.toIntExact(getSize())];
			int    read=stream.readNBytes(data, 0, data.length);
			if(DEBUG_VALIDATION){
				Assert(read==data.length, "Failed to read data amount specified by getSize() =", data.length, "read =", read);
			}
			return data;
		}
	}
}
