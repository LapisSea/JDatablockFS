package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.internal.IUtils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.IOTransactionBuffer;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.util.NotNull;

import java.io.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;

public final class IOFileData implements IOInterface, Closeable{
	
	private enum Mode{
		READ_ONLY("r"),
		READ_WRITE("rw"),
		READ_WRITE_SYNCHRONOUS("rwd");
		
		final String str;
		Mode(String str){this.str=str;}
	}
	
	private static final boolean FORCE_SYNCHRONOUS=GlobalConfig.configFlag("io.synchronousFileIO", false);
	
	@SuppressWarnings("resource")
	public class FileRandomIO implements RandomIO{
		
		private long pos;
		
		public FileRandomIO(){}
		
		public FileRandomIO(int pos){
			if(pos<0) throw new IndexOutOfBoundsException(pos);
			this.pos=pos;
		}
		
		@Override
		public FileRandomIO setPos(long pos){
			if(pos<0) throw new IndexOutOfBoundsException();
			this.pos=Math.toIntExact(pos);
			return this;
		}
		
		@Override
		public long getPos(){
			return Math.min(pos, getSize());
		}
		
		@Override
		public long getSize(){
			return IOFileData.this.used;
		}
		
		@Override
		public void setSize(long targetSize){
			if(targetSize<0) throw new IllegalArgumentException();
			if(transactionOpen) throw new UnsupportedOperationException();
			var cap=getCapacity();
			if(targetSize>cap) targetSize=cap;
			IOFileData.this.used=Math.toIntExact(targetSize);
		}
		
		@Override
		public long getCapacity(){
			if(transactionOpen){
				return transactionBuff.getCapacity(used);
			}
			return used;
		}
		
		@Override
		public FileRandomIO setCapacity(long newCapacity) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			
			IOFileData.this.setCapacity(newCapacity);
			pos=(int)Math.min(pos, getSize());
			return this;
		}
		
		@Override
		public void close(){}
		
		@Override
		public void flush(){}
		
		@Override
		public int read() throws IOException{
			if(transactionOpen){
				int b=transactionBuff.readByte(this::readAt, pos);
				if(b>=0){
					this.pos++;
				}
				return b;
			}
			
			int remaining=(int)(getSize()-getPos());
			if(remaining<=0) return -1;
			return read1(pos++)&0xFF;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			if(transactionOpen){
				int read=transactionBuff.read(this::readAt, pos, b, off, len);
				pos+=read;
				return read;
			}
			
			int read=readAt(pos, b, off, len);
			if(read>=0) pos+=read;
			return read;
		}
		
		@Override
		public long readWord(int len) throws IOException{
			if(transactionOpen){
				var word=transactionBuff.readWord(this::readAt, pos, len);
				pos+=len;
				return word;
			}
			
			long remaining=used-pos;
			if(remaining<len){
				throw new EOFException();
			}
			
			long val=IOFileData.this.read8(pos, len);
			pos+=len;
			return val;
		}
		
		private int readAt(long pos, byte[] b, int off, int len) throws IOException{
			int remaining=(int)(getSize()-pos);
			if(remaining<=0) return -1;
			
			int clampedLen=Math.min(remaining, len);
			readN(pos, b, off, clampedLen);
			return clampedLen;
		}
		
		@Override
		public void write(int b) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.writeByte(pos, b);
				pos++;
				return;
			}
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity()+1, getCapacity()+1-remaining)));
			write1(pos, (byte)b);
			pos++;
			used=Math.max(used, pos);
		}
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			write(b, off, len, true);
		}
		
		private void write(byte[] b, int off, int len, boolean pushPos) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.write(pos, b, off, len);
				if(pushPos) pos+=len;
				return;
			}
			
			var oldPos=pos;
			write0(b, off, len);
			
			if(pushPos){
				pos+=len;
				used=Math.max(used, pos);
			}
		}
		
		@Override
		public void writeAtOffsets(Collection<WriteChunk> writeData) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			if(writeData.isEmpty()) return;
			if(transactionOpen){
				for(var e : writeData){
					transactionBuff.write(e.ioOffset(), e.data(), e.dataOffset(), e.dataLength());
				}
				return;
			}
			
			var required=writeData.stream().mapToLong(WriteChunk::ioEnd).max().orElseThrow();
			if(getCapacity()<required) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), required)));
			
			used=Math.max(used, Math.toIntExact(required));
			
			for(var e : writeData){
				writeN(e.data(), e.dataOffset(), Math.toIntExact(e.ioOffset()), e.dataLength());
			}
		}
		
		private void write0(byte[] b, int off, int len) throws IOException{
			if(len==0) return;
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity()+len-remaining)));
			
			writeN(b, off, pos, len);
		}
		
		@Override
		public void writeWord(long v, int len) throws IOException{
			if(transactionOpen){
				transactionBuff.writeWord(pos, v, len);
				pos+=len;
				return;
			}
			
			if(len==0) return;
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity()+len-remaining)));
			
			IOFileData.this.write8(v, pos, len);
			pos+=len;
			used=Math.max(used, pos);
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			
			IUtils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
		}
		@Override
		public boolean isReadOnly(){
			return readOnly;
		}
		
		@Override
		public String toString(){
			int count=64;
			
			int start=(int)getPos(), end=start+count;
			
			var used=(int)getSize();
			
			int overshoot=end-used;
			if(overshoot>0){
				start=Math.max(0, start-overshoot);
				end=used;
			}
			
			String transactionStr=transactionOpen?", transaction: {"+transactionBuff.infoString()+"}":"";
			
			String name=getClass().getSimpleName();
			String pre ="{pos="+getPos()+transactionStr;
			if(start!=0||start!=end){
				pre+=", data=";
			}
			if(start!=0) pre+=start+" ... ";
			
			var more=used-end;
			var post=more==0?"}":" ... "+more+"}";
			
			var result=new StringBuilder(name.length()+pre.length()+post.length()+end-start);
			
			result.append(name).append(pre);
			try(var io=ioAt(start)){
				for(int i=start;i<end-1;i++){
					char c=(char)io.readInt1();
					result.append(switch(c){
						case 0 -> '␀';
						case '\n' -> '↵';
						case '\r' -> '®';
						case '\b' -> '␈';
						case '\t' -> '↹';
						default -> c;
					});
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			
			result.append(post);
			
			return result.toString();
		}
		@Override
		public boolean isDirect(){
			return !transactionOpen;
		}
	}
	
	private final File             file;
	private final RandomAccessFile fileData;
	private       long             used;
	
	private final boolean readOnly;
	
	@SuppressWarnings("unused")
	private       boolean             transactionOpen;
	private final IOTransactionBuffer transactionBuff=new IOTransactionBuffer();
	
	public IOFileData(File file) throws IOException{this(file, false);}
	public IOFileData(File file, boolean readOnly) throws IOException{
		this.file=file;
		this.readOnly=readOnly;
		
		Mode mode;
		if(readOnly) mode=Mode.READ_ONLY;
		else mode=FORCE_SYNCHRONOUS?Mode.READ_WRITE_SYNCHRONOUS:Mode.READ_WRITE;
		fileData=new RandomAccessFile(file, mode.str);
		
		this.used=getLength();
	}
	
	@Override
	@NotNull
	public FileRandomIO io(){
		return new FileRandomIO();
	}
	@Override
	public RandomIO ioAt(long offset) throws IOException{
		return new FileRandomIO((int)offset);
	}
	
	@Override
	public long getIOSize(){
		if(transactionOpen){
			return transactionBuff.getCapacity(used);
		}
		return used;
	}
	
	private void setCapacity(long newCapacity) throws IOException{
		if(readOnly) throw new UnsupportedOperationException();
		if(transactionOpen){
			var siz=transactionBuff.getCapacity(used);
			transactionBuff.capacityChange(Math.min(siz, newCapacity));
			return;
		}
		
		long lastCapacity=getLength();
		if(lastCapacity==newCapacity) return;
		
		used=Math.min(used, newCapacity);
		resize(used);
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	private static final VarHandle TRANSACTION_OPEN;
	
	static{
		try{
			TRANSACTION_OPEN=MethodHandles.lookup().findVarHandle(IOFileData.class, "transactionOpen", boolean.class);
		}catch(ReflectiveOperationException e){
			throw new Error(e);
		}
	}
	
	@Override
	public IOTransaction openIOTransaction(){
		return transactionBuff.open(this, TRANSACTION_OPEN);
	}
	
	@Override
	public byte[] readAll() throws IOException{
		if(transactionOpen) return IOInterface.super.readAll();
		var usedI=Math.toIntExact(used);
		var copy =new byte[usedI];
		readN(0, copy, 0, usedI);
		return copy;
	}
	
	@Override
	public String toString(){
		return IOFileData.class.getSimpleName()+"."+getClass().getSimpleName()+"#"+Integer.toHexString(hashCode());
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof IOFileData that&&
		       fileData.equals(that.fileData);
	}
	
	@Override
	public int hashCode(){
		return fileData.hashCode();
	}
	
	private long getLength() throws IOException{
		return fileData.length();
	}
	private void resize(long newSize) throws IOException{
		fileData.setLength(newSize);
	}
	
	private byte read1(long fileOffset) throws IOException{
		fileData.seek(fileOffset);
		return fileData.readByte();
	}
	private void write1(long fileOffset, byte b) throws IOException{
		fileData.seek(fileOffset);
		fileData.writeByte(b);
	}
	
	private void readN(long fileOffset, byte[] dest, int off, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.readFully(dest, off, len);
	}
	private void writeN(byte[] src, int index, long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		fileData.write(src, index, len);
	}
	
	private long read8(long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff=new byte[len];
		fileData.readFully(buff);
		return Utils.read8(buff, 0, len);
	}
	private void write8(long value, long fileOffset, int len) throws IOException{
		fileData.seek(fileOffset);
		byte[] buff=new byte[len];
		Utils.write8(value, buff, 0, len);
		fileData.write(buff);
	}
	
	@Override
	public IOFileData asReadOnly(){
		if(isReadOnly()) return this;
		try{
			close();
			return new IOFileData(file, true);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	@Override
	public void close() throws IOException{
		fileData.close();
	}
}
