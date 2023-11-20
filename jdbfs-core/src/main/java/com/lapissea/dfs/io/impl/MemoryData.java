package com.lapissea.dfs.io.impl;

import com.lapissea.dfs.internal.WordIO;
import com.lapissea.dfs.io.IOHook;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

public final class MemoryData extends CursorIOData{
	
	private       byte[] fileData;
	private final int    hash;
	
	private MemoryData(byte[] fileData, Builder info){
		super(info.getOnWrite(), info.isReadOnly());
		this.fileData = Objects.requireNonNull(fileData);
		
		var ok = getLength()>=used;
		if(!ok) throw new IllegalArgumentException(TextUtil.toString(getLength(), ">=", used));
		
		this.used = info.getUsed() == -1? getLength() : info.getUsed();
		hash = System.identityHashCode(this);
	}
	
	@Override
	public boolean equals(Object o){
		return o instanceof MemoryData that &&
		       Arrays.equals(fileData, that.fileData);
	}
	
	@Override
	public int hashCode(){
		return hash;
	}
	@Override
	protected long getLength(){
		return fileData.length;
	}
	@Override
	protected byte read1(long fileOffset){
		return fileData[(int)fileOffset];
	}
	@Override
	protected void write1(long fileOffset, byte b){
		fileData[(int)fileOffset] = b;
	}
	@Override
	protected void readN(long fileOffset, byte[] dest, int off, int len){
		System.arraycopy(fileData, (int)fileOffset, dest, off, len);
	}
	@Override
	protected void writeN(byte[] src, int srcOffset, long fileOffset, int len){
		System.arraycopy(src, srcOffset, fileData, (int)fileOffset, len);
	}
	@Override
	protected void resize(long newFileSize){
		if(newFileSize>Integer.MAX_VALUE) throw new OutOfMemoryError();
		var oldFileData = fileData;
		fileData = Arrays.copyOf(oldFileData, (int)newFileSize);
	}
	
	@Override
	protected long readWord(long fileOffset, int len){
		return WordIO.getWord(fileData, (int)fileOffset, len);
	}
	@Override
	protected void writeWord(long value, long fileOffset, int len){
		WordIO.setWord(value, fileData, (int)fileOffset, len);
	}
	
	public static MemoryData empty(){
		return new Builder().build();
	}
	public static Builder builder(){
		return new Builder();
	}
	
	public static MemoryData viewOf(byte[] data){
		return new MemoryData(data, new Builder().asReadOnly());
	}
	
	public static MemoryData of(byte[] data){
		return builder().withRaw(data).build();
	}
	
	public interface DataInitializer{
		void init(ContentWriter dest) throws IOException;
	}
	
	@SuppressWarnings("unused")
	public static class Builder{
		private UnsafeSupplier<Object, IOException> dataProducer;
		private boolean                             readOnly = false;
		private int                                 used     = -1;
		private IOHook                              onWrite;
		
		public Builder withInitial(DataInitializer init){
			this.dataProducer = () -> {
				var builder = new ContentOutputBuilder(32);
				init.init(builder);
				return builder.toByteArray();
			};
			return this;
		}
		
		public Builder withCapacity(int capacity){
			this.dataProducer = () -> new byte[capacity];
			return this;
		}
		public Builder withData(IOInterface data){
			this.dataProducer = data::readAll;
			return this;
		}
		
		public Builder withRaw(byte[] data){
			var clone = data.clone();
			this.dataProducer = () -> clone;
			return this;
		}
		
		public Builder withRaw(ByteBuffer data){
			var d = ByteBuffer.allocate(data.limit());
			d.put(data.position(0));
			d.position(0);
			return withRaw0(d);
		}
		
		private Builder withRaw0(Object data){
			Objects.requireNonNull(data);
			this.dataProducer = () -> data;
			return this;
		}
		
		public Builder asReadOnly(){
			this.readOnly = true;
			return this;
		}
		
		public Builder withUsedLength(int used){
			this.used = used;
			return this;
		}
		
		public Builder withOnWrite(IOHook onWrite){
			this.onWrite = onWrite;
			return this;
		}
		
		private Object readData() throws IOException{
			if(dataProducer == null) return ZeroArrays.ZERO_BYTE;
			return dataProducer.get();
		}
		
		public IOHook getOnWrite(){
			return onWrite;
		}
		
		public int getUsed(){
			return used;
		}
		
		public boolean isReadOnly(){
			return readOnly;
		}
		
		public MemoryData build(){
			Object actualData;
			try{
				actualData = readData();
			}catch(IOException e){
				throw new RuntimeException(e);
			}
			dataProducer = null;
			
			return new MemoryData(switch(actualData){
				case byte[] data -> data;
				case ByteBuffer data -> {
					var b = new byte[data.limit()];
					data.get(0, b);
					yield b;
				}
				default -> throw new RuntimeException("unknown data type " + actualData);
			}, this);
		}
	}
	
	@Override
	public MemoryData asReadOnly(){
		if(isReadOnly()) return this;
		return builder().withRaw(fileData).withUsedLength((int)used).asReadOnly().build();
	}
	
}
