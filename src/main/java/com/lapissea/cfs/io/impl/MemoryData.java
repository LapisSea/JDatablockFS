package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.IOTransactionBuffer;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

public abstract class MemoryData<DataType> implements IOInterface{
	
	public class MemRandomIO implements RandomIO{
		
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
			if(transactionOpen){
				throw new NotImplementedException();//TODO implement capacity changing when in transaction
			}
			
			MemoryData.this.setCapacity(newCapacity);
			pos=Math.min(pos, used);
			return this;
		}
		
		@Override
		public void close(){}
		
		@Override
		public void flush(){}
		
		@Override
		public int read() throws IOException{
			if(transactionOpen){
				int b=transactions.readByte(this::readAt, pos);
				if(b>=0){
					this.pos++;
				}
				return b;
			}
			
			int remaining=(int)(getSize()-getPos());
			if(remaining<=0) return -1;
			return read1(data, pos++)&0xFF;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			if(transactionOpen){
				int read=transactions.read(this::readAt, pos, b, off, len);
				pos+=read;
				return read;
			}
			
			int read=readAt(pos, b, off, len);
			if(read>=0) pos+=read;
			return read;
		}
		
		private int readAt(long pos, byte[] b, int off, int len){
			return readAt((int)pos, b, off, len);
		}
		private int readAt(int pos, byte[] b, int off, int len){
			int remaining=(int)(getSize()-pos);
			if(remaining<=0) return -1;
			
			int clampedLen=Math.min(remaining, len);
			readN(data, pos, b, off, clampedLen);
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
			if(transactionOpen){
				transactions.writeByte(pos, b);
				pos++;
				return;
			}
			
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
			if(transactionOpen){
				transactions.write(pos, b, off, len);
				if(pushPos) pos+=len;
				return;
			}
			
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
		public boolean isReadOnly(){
			return readOnly;
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
		@Override
		public boolean isDirect(){
			return true;
		}
	}
	
	public transient EventLogger onWrite;
	
	private DataType data;
	private int      used;
	
	private final boolean readOnly;
	
	private       boolean             transactionOpen;
	private final IOTransactionBuffer transactions=new IOTransactionBuffer();
	
	public MemoryData(DataType data, Builder info){
		
		var ok=getLength(data)>=used;
		if(!ok) throw new IllegalArgumentException(TextUtil.toString(getLength(data), ">=", used));
		
		this.data=data;
		this.used=info.getUsed()==-1?getLength(data):info.getUsed();
		this.readOnly=info.isReadOnly();
		
		onWrite=info.getOnWrite();
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
			onWrite.log(this, ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
	}
	
	@Override
	@NotNull
	public MemRandomIO io(){
		return new MemRandomIO();
	}
	
	@Override
	public long getIOSize(){
		return used;
	}
	
	private void setCapacity(long newCapacity){
		setCapacity(Math.toIntExact(newCapacity));
	}
	private void setCapacity(int newCapacity){
		if(readOnly) throw new UnsupportedOperationException();
		if(transactionOpen){
			transactions.capacityChange(newCapacity);
		}
		
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
	public Trans openIOTransaction(){
		var oldTransactionOpen=transactionOpen;
		transactionOpen=true;
		return ()->{
			transactionOpen=oldTransactionOpen;
			if(!oldTransactionOpen){
				transactions.merge(this);
			}
		};
	}
	
	public String toShortString(){
		return TextUtil.mapObjectValues(this).entrySet().stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "{", "}"));
	}
	@Override
	public String toString(){
		return MemoryData.class.getSimpleName()+toShortString();
	}
	
	protected abstract int getLength(DataType data);
	protected abstract DataType resize(DataType oldData, int newSize);
	
	protected abstract byte read1(DataType data, int i);
	protected abstract void write1(DataType data, int i, byte b);
	protected abstract void readN(DataType src, int index, byte[] dest, int off, int len);
	protected abstract void writeN(byte[] src, int index, DataType dest, int off, int len);
	
	public static Builder build(){
		return new Builder();
	}
	
	public interface EventLogger{
		void log(MemoryData<?> data, LongStream ids) throws IOException;
	}
	
	public interface DataInitializer{
		void init(ContentWriter dest) throws IOException;
	}
	
	public static class Builder{
		private UnsafeSupplier<Object, IOException> dataProducer;
		private boolean                             readOnly=false;
		private int                                 used    =-1;
		private EventLogger                         onWrite;
		
		public Builder withInitial(DataInitializer init){
			this.dataProducer=()->{
				var builder=new ContentOutputBuilder(32);
				init.init(builder);
				return builder.toByteArray();
			};
			return this;
		}
		
		public Builder withCapacity(int capacity){
			this.dataProducer=()->new byte[capacity];
			return this;
		}
		public Builder withData(IOInterface data){
			this.dataProducer=data::readAll;
			return this;
		}
		
		public Builder withRaw(byte[] data){
			return withRaw0(data);
		}
		public Builder withRaw(ByteBuffer data){
			return withRaw0(data);
		}
		
		private Builder withRaw0(Object data){
			Objects.requireNonNull(data);
			this.dataProducer=()->data;
			return this;
		}
		
		public Builder asReadOnly(){
			this.readOnly=true;
			return this;
		}
		
		public Builder withUsedLength(int used){
			this.used=used;
			return this;
		}
		
		public Builder withOnWrite(EventLogger onWrite){
			this.onWrite=onWrite;
			return this;
		}
		
		private Object readData() throws IOException{
			if(dataProducer==null) return withCapacity(32).readData();
			
			return dataProducer.get();
		}
		
		public EventLogger getOnWrite(){
			return onWrite;
		}
		
		public int getUsed(){
			return used;
		}
		
		public boolean isReadOnly(){
			return readOnly;
		}
		
		public MemoryData<?> build() throws IOException{
			var actualData=readData();
			dataProducer=null;
			
			return switch(actualData){
				case byte[] data -> new Arr(data, this);
				case ByteBuffer data -> new Buff(data, this);
				default -> throw new RuntimeException("unknown data type "+actualData);
			};
		}
	}
	
	private static final class Arr extends MemoryData<byte[]>{
		
		public Arr(byte[] data, Builder info){
			super(data, info);
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
	
	private static final class Buff extends MemoryData<ByteBuffer>{
		
		public Buff(ByteBuffer data, Builder info){
			super(data, info);
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
