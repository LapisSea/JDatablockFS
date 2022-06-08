package com.lapissea.cfs.io.impl;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.IOTransactionBuffer;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.content.ContentOutputBuilder;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.EOFException;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;
import java.util.stream.LongStream;

public abstract class MemoryData<DataType> implements IOInterface{
	
	public class MemRandomIO implements RandomIO{
		
		private int pos;
		
		public MemRandomIO(){}
		
		public MemRandomIO(int pos){
			if(pos<0) throw new IndexOutOfBoundsException(pos);
			this.pos=pos;
		}
		
		@Override
		public MemRandomIO setPos(long pos){
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
			return getCapacity();
		}
		
		@Override
		public void setSize(long targetSize){
			throw new UnsupportedOperationException();
		}
		
		@Override
		public long getCapacity(){
			if(transactionOpen){
				return transactionBuff.getCapacity(used);
			}
			return used;
		}
		
		@Override
		public MemRandomIO setCapacity(long newCapacity){
			if(readOnly) throw new UnsupportedOperationException();
			
			MemoryData.this.setCapacity(newCapacity);
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
			return read1(data, pos++)&0xFF;
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
			if(transactionOpen){//TODO: Implement transaction read8
				return RandomIO.super.readWord(len);
			}
			
			int remaining=used-pos;
			if(remaining<len){
				throw new EOFException();
			}
			
			long val=MemoryData.this.read8(data, pos, len);
			pos+=len;
			return val;
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
		
		@Override
		public void write(int b){
			if(readOnly) throw new UnsupportedOperationException();
			if(transactionOpen){
				transactionBuff.writeByte(pos, b);
				pos++;
				return;
			}
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity()+1, getCapacity()+1-remaining)));
			write1(data, pos, (byte)b);
			logWriteEvent(pos);
			pos++;
			used=Math.max(used, pos);
		}
		
		@Override
		public void write(byte[] b, int off, int len){
			write(b, off, len, true);
		}
		
		private void write(byte[] b, int off, int len, boolean pushPos){
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
			logWriteEvent(oldPos, oldPos+len);
		}
		
		@Override
		public void writeAtOffsets(Collection<WriteChunk> writeData){
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
				writeN(e.data(), e.dataOffset(), data, Math.toIntExact(e.ioOffset()), e.dataLength());
			}
			
			if(onWrite!=null){
				logWriteEvent(writeData.stream().flatMapToLong(e->LongStream.range(e.ioOffset(), e.ioOffset()+e.dataLength())));
			}
		}
		
		private void write0(byte[] b, int off, int len){
			if(len==0) return;
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity()+len-remaining)));
			
			writeN(b, off, data, pos, len);
		}
		
		@Override
		public void write8(long v, int len) throws IOException{
			if(transactionOpen){//TODO: Implement transaction read8
				RandomIO.super.write8(v, len);
				return;
			}
			
			if(len==0) return;
			
			int remaining=(int)(getCapacity()-getPos());
			if(remaining<len) setCapacity(Math.max(4, Math.max((int)(getCapacity()*4D/3), getCapacity()+len-remaining)));
			
			MemoryData.this.write8(v, data, pos, len);
			var oldPos=pos;
			pos+=len;
			used=Math.max(used, pos);
			logWriteEvent(oldPos, oldPos+len);
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
			
			var used=(int)getSize();
			
			int overshoot=end-used;
			if(overshoot>0){
				start=Math.max(0, start-overshoot);
				end=used;
			}
			
			String transactionStr=transactionOpen?", transaction: {"+transactionBuff.infoString()+"}":"";
			
			String name=getClass().getSimpleName();
			String pre ="{pos="+pos+transactionStr+", data=";
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
			return true;
		}
	}
	
	public transient EventLogger onWrite;
	
	private DataType data;
	private int      used;
	
	private final boolean readOnly;
	
	private       boolean             transactionOpen;
	private final IOTransactionBuffer transactionBuff=new IOTransactionBuffer();
	
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
	public RandomIO ioAt(long offset) throws IOException{
		return new MemRandomIO((int)offset);
	}
	
	@Override
	public long getIOSize(){
		if(transactionOpen){
			return transactionBuff.getCapacity(used);
		}
		return used;
	}
	
	private void setCapacity(long newCapacity){
		setCapacity(Math.toIntExact(newCapacity));
	}
	private void setCapacity(int newCapacity){
		if(readOnly) throw new UnsupportedOperationException();
		if(transactionOpen){
			var siz=transactionBuff.getCapacity(used);
			transactionBuff.capacityChange(Math.min(siz, newCapacity));
			return;
		}
		
		long lastCapacity=getLength(data);
		if(lastCapacity==newCapacity) return;
		
		data=resize(data, newCapacity);
		used=Math.min(used, newCapacity);
		
		logWriteEvent(lastCapacity, newCapacity);
	}
	
	@Override
	public boolean isReadOnly(){
		return readOnly;
	}
	
	private static final VarHandle TRANSACTION_OPEN;
	
	static{
		try{
			TRANSACTION_OPEN=MethodHandles.lookup().findVarHandle(MemoryData.class, "transactionOpen", boolean.class);
		}catch(ReflectiveOperationException e){
			throw new Error(e);
		}
	}
	
	@Override
	public IOTransaction openIOTransaction(){
		return transactionBuff.open(this, TRANSACTION_OPEN);
	}
	
	@Override
	public String toString(){
		return MemoryData.class.getSimpleName()+"."+getClass().getSimpleName()+"#"+Integer.toHexString(hashCode());
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof MemoryData<?> that&&
		       data.equals(that.data);
	}
	
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	
	protected abstract int getLength(DataType data);
	protected abstract DataType resize(DataType oldData, int newSize);
	
	protected abstract byte read1(DataType data, int i);
	protected abstract void write1(DataType data, int i, byte b);
	protected abstract void readN(DataType src, int index, byte[] dest, int off, int len);
	protected abstract void writeN(byte[] src, int index, DataType dest, int off, int len);
	
	protected abstract long read8(DataType data, int index, int len);
	protected abstract void write8(long src, DataType dest, int off, int len);
	
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
		
		@Override
		protected long read8(byte[] data, int index, int len){
			return Utils.read8(data, index, len);
		}
		@Override
		protected void write8(long src, byte[] dest, int off, int len){
			Utils.write8(src, dest, off, len);
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
		
		@Override
		protected long read8(ByteBuffer data, int index, int len){
			final var lm1=len-1;
			long      val=0;
			for(int i=0;i<len;i++){
				val|=(data.get(index+i)&255L)<<((lm1-i)*8);
			}
			return val;
		}
		
		@Override
		protected void write8(long src, ByteBuffer dest, int off, int len){
			final var lm1=len-1;
			
			for(int i=0;i<len;i++){
				dest.put(off+i, (byte)(src >>> ((lm1-i)*8)));
			}
		}
		
	}
}
