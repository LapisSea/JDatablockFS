package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.LongStream;

public abstract class MemoryData<DataType> implements IOInterface{
	private class MemRandomIO implements RandomIO{
		
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
			return used;
		}
		
		@Override
		public void setSize(long targetSize){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public long getCapacity(){
			return used;
		}
		
		@Override
		public RandomIO setCapacity(long newCapacity){
			if(readOnly) throw new UnsupportedOperationException();
			
			MemoryData.this.setCapacity(newCapacity);
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
			return read1(data, pos++)&0xFF;
		}
		
		@Override
		public int read(byte[] b, int off, int len){
			int remaining=(int)(getSize()-getPos());
			if(remaining<=0) return -1;
			
			int clampedLen=Math.min(remaining, len);
			readN(data, pos, b, off, len);
			pos+=clampedLen;
			return clampedLen;
		}
		
		private void pushUsed(){
			if(used<pos){
				var old=used;
				used=pos;
				
				logWriteEvent(old, used);
			}
		}
		
		@Override
		public void write(int b){
			if(readOnly) throw new UnsupportedOperationException();
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity()+1, getCapacity()+1-remaining)));
			write1(data, pos, (byte)b);
			logWriteEvent(pos);
			pos++;
			pushUsed();
		}
		
		@Override
		public void write(byte[] b, int off, int len){
			write(b, off, len, true);
		}
		
		public void write(byte[] b, int off, int len, boolean pushPos){
			if(readOnly) throw new UnsupportedOperationException();
			if(len==0) return;
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity()+len-remaining)));
			
			writeN(b, off, data, pos, len);
			logWriteEvent(pos, pos+len);
			if(pushPos){
				pos+=len;
				pushUsed();
			}
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			if(readOnly) throw new UnsupportedOperationException();
			
			Utils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
		}
		
		@Override
		public long getGlobalPos(){
			return -1;
		}
		
		@Override
		public String toString(){
			int count=64;
			
			int start=pos, end=start+count;
			
			int overshoot=end-used;
			if(overshoot>0){
//				start-=overshoot;
				end=used;
			}
			
			String name=getClass().getSimpleName();
			String pre;
			if(start<0){
				start=0;
				pre="{pos="+pos+", data=";
			}else pre="{";
			
			var post=overshoot==0?"}":" + "+overshoot+" more}";
			
			var result=new StringBuilder(name.length()+pre.length()+post.length()+end-start);
			
			result.append(name).append(pre);
			for(int i=start;i<end;i++){
				char c=(char)read1(data, i);
				result.append(switch(c){
					case 0 -> '␀';
					case '\n' -> '↵';
					case '\t' -> '↹';
					default -> c;
				});
			}
			result.append(post);
			
			return result.toString();
		}
	}
	
	public transient UnsafeConsumer<LongStream, IOException> onWrite;
	
	private DataType data;
	private int      used;
	
	private final boolean readOnly;
	
	public MemoryData(DataType data, int used, boolean readOnly){
		var ok=getLength(data)>=used;
		if(!ok) throw new IllegalArgumentException(TextUtil.toString(getLength(data), ">=", used));
		
		this.data=data;
		this.used=used;
		this.readOnly=readOnly;
	}
	
	private void logWriteEvent(long single){
		if(onWrite!=null){
			logWriteEvent(LongStream.of(single));
		}
	}
	private void logWriteEvent(long start, long end){
		if(onWrite!=null){
			logWriteEvent(LongStream.range(start, end));
		}
	}
	private void logWriteEvent(LongStream ids){
		try{
			onWrite.accept(ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
	}
	
	@Override
	@NotNull
	public RandomIO io(){
		return new MemRandomIO();
	}
	
	@Override
	public long getIOSize(){
		return used;
	}
	
	public void setCapacity(long newCapacity){
		setCapacity(Math.toIntExact(newCapacity));
	}
	private void setCapacity(int newCapacity){
		if(readOnly) throw new UnsupportedOperationException();
		
		long lastCapacity=getIOSize();
		if(lastCapacity==newCapacity) return;
		
		data=resize(data, newCapacity);
		used=Math.min(used, newCapacity);
		
		logWriteEvent(lastCapacity, newCapacity);
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	@Override
	public String toString(){
		int max=40;
		if(used>max){
			try{
				return hexdump();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		StringBuilder result=new StringBuilder(used*3+1).append('[');
		for(int i=0;i<used;i++){
			var ay=Integer.toHexString(read1(data, i)&0xFF).toUpperCase();
			if(ay.length()==1) result.append('0');
			result.append(ay);
			if(i+1<used) result.append('|');
		}
		result.append(']');
		return result.toString();
	}
	
	protected abstract int getLength(DataType data);
	protected abstract DataType resize(DataType oldData, int newSize);
	
	protected abstract byte read1(DataType data, int i);
	protected abstract void write1(DataType data, int i, byte b);
	protected abstract void readN(DataType src, int index, byte[] dest, int off, int len);
	protected abstract void writeN(byte[] src, int index, DataType dest, int off, int len);
	
	
	public static IOInterface from(byte[] data, boolean readOnly){
		return new Arr(data, readOnly);
	}
	public static IOInterface from(ByteBuffer data, boolean readOnly){
		return new Buff(data, readOnly);
	}
	
	public static class Arr extends MemoryData<byte[]>{
		
		public Arr(IOInterface data, boolean readOnly) throws IOException{
			this(data.readAll(), readOnly);
		}
		
		public Arr(byte[] data, boolean readOnly){
			this(data, data.length, readOnly);
		}
		public Arr(int dataSize, boolean readOnly){
			this(new byte[dataSize], readOnly);
		}
		
		public Arr(int dataSize){
			this(dataSize, false);
		}
		
		public Arr(){
			this(false);
		}
		
		public Arr(boolean readOnly){
			this(new byte[4], 0, readOnly);
		}
		
		public Arr(byte[] data, int used, boolean readOnly){
			super(data, used, readOnly);
		}
		
		@Override
		protected int getLength(byte[] data){
			return data.length;
		}
		@Override
		protected byte read1(byte[] data, int i){
			return data[i];
		}
		@Override
		protected void write1(byte[] data, int i, byte b){
			data[i]=b;
		}
		@Override
		protected void readN(byte[] src, int index, byte[] dest, int off, int len){
			System.arraycopy(src, index, dest, off, len);
		}
		@Override
		protected void writeN(byte[] src, int index, byte[] dest, int off, int len){
			System.arraycopy(src, index, dest, off, len);
		}
		@Override
		protected byte[] resize(byte[] oldData, int newSize){
			return Arrays.copyOf(oldData, newSize);
		}
	}
	
	public static class Buff extends MemoryData<ByteBuffer>{
		
		public Buff(IOInterface data, boolean readOnly) throws IOException{
			this(ByteBuffer.wrap(data.readAll()), readOnly);
		}
		
		public Buff(ByteBuffer data, boolean readOnly){
			this(data, data.limit(), readOnly);
		}
		public Buff(int dataSize, boolean readOnly){
			this(ByteBuffer.allocate(dataSize), readOnly);
		}
		
		public Buff(int dataSize){
			this(dataSize, false);
		}
		
		public Buff(){
			this(false);
		}
		
		public Buff(boolean readOnly){
			this(ByteBuffer.allocate(4), 0, readOnly);
		}
		
		public Buff(ByteBuffer data, int used, boolean readOnly){
			super(data, used, readOnly);
		}
		@Override
		protected int getLength(ByteBuffer data){
			return data.limit();
		}
		@Override
		protected byte read1(ByteBuffer data, int i){
			return data.get(i);
		}
		@Override
		protected void write1(ByteBuffer data, int i, byte b){
			data.put(i, b);
		}
		@Override
		protected void readN(ByteBuffer src, int index, byte[] dest, int off, int len){
			src.get(index, dest, off, len);
		}
		@Override
		protected void writeN(byte[] src, int index, ByteBuffer dest, int off, int len){
			dest.put(off, src, index, len);
		}
		@Override
		protected ByteBuffer resize(ByteBuffer oldData, int newSize){
			ByteBuffer newData=ByteBuffer.allocate(newSize);
			oldData.position(0);
			newData.put(oldData);
			newData.position(0);
			return newData;
		}
	}
}
