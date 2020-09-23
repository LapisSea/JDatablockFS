package com.lapissea.cfs.io.impl;


import com.lapissea.cfs.Utils;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.LongStream;

public class MemoryData implements IOInterface{
	
	private class MemoryRandomIO implements RandomIO{
		
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
		public void setSize(long targetSize){
			if(readOnly) throw new UnsupportedOperationException();
			
			MemoryData.this.setSize(targetSize);
			pos=Math.min(pos, used);
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
		
		private void pushUsed(){
			if(used<pos){
				var old=used;
				used=pos;
				
				if(onWrite!=null){
					pushOnWrite(LongStream.range(old, used).toArray());
				}
			}
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
			
			System.arraycopy(b, off, bb, pos, len);
			if(onWrite!=null){
				pushOnWrite(LongStream.range(pos, pos+len).toArray());
			}
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
			return getPos();
		}
		
		@Override
		public String toString(){
			int count=64;
			
			int start=pos-count/2, end=start+count;
			
			int overshoot=end-used;
			if(overshoot>0){
//				start-=overshoot;
				end=used;
			}
			
			String pre;
			if(start<0){
				start=0;
				pre="MemoryRandomIO{pos="+pos+", data=";
			}else pre="MemoryRandomIO{";
			
			var post="}";
			
			var result=new StringBuilder(pre.length()+post.length()+end-start);
			
			result.append(pre);
			for(int i=start;i<end;i++){
				result.append((char)bb[i]);
			}
			result.append(post);
			
			return result.toString();
		}
	}
	
	
	////////////////////////////////////////////////////////////////////////////////
	
	
	public transient UnsafeConsumer<long[], IOException> onWrite;
	
	private byte[] bb;
	private int    used;
	
	private final boolean readOnly;
	
	public MemoryData(IOInterface data, boolean readOnly) throws IOException{
		this(data.readAll(), readOnly);
	}
	
	public MemoryData(byte[] data, boolean readOnly){
		this(data, data.length, readOnly);
	}
	public MemoryData(int dataSize, boolean readOnly){
		this(new byte[dataSize], readOnly);
	}
	
	public MemoryData(int dataSize){
		this(dataSize, false);
	}
	
	public MemoryData(){
		this(false);
	}
	
	public MemoryData(boolean readOnly){
		this(new byte[4], 0, readOnly);
	}
	
	public MemoryData(byte[] data, int used, boolean readOnly){
		var ok=data.length>=used;
		if(!ok) throw new IllegalArgumentException(TextUtil.toString(data.length, ">=", used));
		
		bb=data;
		this.used=used;
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
	@NotNull
	public RandomIO io(){
		return new MemoryRandomIO();
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
		return used;
	}
	
	@Override
	public void setCapacity(long newCapacity){
		setCapacity(Math.toIntExact(newCapacity));
	}
	private void setCapacity(int newCapacity){
		if(readOnly) throw new UnsupportedOperationException();
		
		if(getCapacity()==newCapacity) return;
		
		bb=Arrays.copyOf(bb, newCapacity);
		used=Math.min(used, newCapacity);
		
		if(onWrite!=null){
			pushOnWrite(ZeroArrays.ZERO_LONG);
		}
	}
	
	@Override
	public String getName(){
		return "mem";
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
		
		StringBuilder result=new StringBuilder("[");
		for(int i=0, j=Math.min(used, max);i<j;i++){
			var ay=Integer.toHexString(bb[i]&0xFF).toUpperCase();
			if(ay.length()==1) result.append('0');
			result.append(ay);
			if(i+1<j) result.append('|');
		}
		if(used>max) result.append("...");
		result.append(']');
		return result.toString();
	}
}
