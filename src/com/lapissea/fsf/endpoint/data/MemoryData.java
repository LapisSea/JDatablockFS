package com.lapissea.fsf.endpoint.data;

import com.lapissea.fsf.Utils;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.RandomIO;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.LongStream;

public class MemoryData implements IOInterface{
	public transient UnsafeConsumer<long[], IOException> onWrite;
	
	private byte[] bb;
	private int    used;
	
	private final boolean readOnly;
	
	public MemoryData(IOInterface data, boolean readOnly) throws IOException{
		bb=data.readAll();
		used=bb.length;
		this.readOnly=readOnly;
	}
	
	public MemoryData(boolean readOnly){
		bb=new byte[4];
		this.readOnly=readOnly;
	}
	
	private void pushOnWrite(long[] ids){
		try{
			onWrite.accept(ids);
		}catch(Throwable e){
			throw new RuntimeException("Exception on write event", e);
		}
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
				return MemoryData.this.getSize();
			}
			
			@Override
			public RandomIO setSize(long targetSize){
				if(readOnly) throw new UnsupportedOperationException();
				
				MemoryData.this.setSize(targetSize);
				pos=Math.min(pos, used);
				return this;
			}
			
			@Override
			public long getCapacity(){
				return MemoryData.this.getCapacity();
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
				if(readOnly) throw new UnsupportedOperationException();
				
				int remaining=(int)(getCapacity()-getPos());
				if(remaining<=0) setCapacity(Math.max(4, Math.max(getCapacity()+1, getCapacity()+1-remaining)));
				bb[pos]=(byte)b;
				if(onWrite!=null){
					pushOnWrite(new long[]{pos});
				}
				pos++;
				if(used<pos) used=pos;
			}
			
			@Override
			public void write(byte[] b, int off, int len){
				write(b, off, len, true);
			}
			
			public void write(byte[] b, int off, int len, boolean pushPos){
				if(readOnly) throw new UnsupportedOperationException();
				
				int remaining=(int)(getCapacity()-getPos());
				if(remaining<len) setCapacity(Math.max(4, Math.max(getCapacity()<<1, getCapacity()+len-remaining)));
				
				System.arraycopy(b, off, bb, pos, len);
				if(onWrite!=null){
					pushOnWrite(LongStream.range(pos, pos+len).toArray());
				}
				if(pushPos){
					pos+=len;
					if(used<pos) used=pos;
				}
			}
			
			@Override
			public void fillZero(long requestedMemory) throws IOException{
				if(readOnly) throw new UnsupportedOperationException();
				
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
	public void setSize(long requestedSize){
		throw new UnsupportedOperationException();
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
		if(readOnly) throw new UnsupportedOperationException();
		
		if(getCapacity()==newCapacity) return;
		bb=Arrays.copyOf(bb, (int)newCapacity);
		used=Math.min(used, bb.length);
	}
}
