package com.lapissea.fsf.chunk;


import com.lapissea.fsf.Utils;
import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.RandomIO;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.fsf.FileSystemInFile.*;
import static com.lapissea.fsf.chunk.ChunkIO.OverflowMode.*;
import static com.lapissea.util.UtilL.*;

/**
 * Internal class that maps file IOInterface to read and expand (when needed) a chunk chain
 */
public class ChunkIO implements IOInterface{
	
	protected enum OverflowMode{
		CLIP,
		EOF,
		EXTEND,
		SET
	}
	
	private class ChunkSpaceRandomIO implements RandomIO{
		RandomIO data;
		
		long chainSpaceDataStart;
		long chainSpaceOffset;
		
		Chunk chunk;
		private boolean finished;
		
		
		ChunkSpaceRandomIO(RandomIO data){
			this.data=data;
			chunks.dependencyInvalidate.add(this::invalidate);
		}
		
		private void checkUsed(Chunk chunk){
			if(DEBUG_VALIDATION){
				Assert(chunk.isUsed(), chunk, chunks);
			}
		}
		
		private void invalidate(){
			chunk=null;
			chainSpaceDataStart=0;
			
			if(DEBUG_VALIDATION){
				chunks.forEach(this::checkUsed);
			}
		}
		
		private void setChunk(int chainInd){
			chunk=chunks.get(chainInd);
			chainSpaceDataStart=chunks.getChainSpaceCapacityOffset(chainInd);
		}
		
		@Override
		public byte[] contentBuf(){
			return data.contentBuf();
		}
		
		private boolean isInRange(long chainSpaceChunkDataStart, long dataSize){
			return chainSpaceChunkDataStart<=chainSpaceOffset&&(chainSpaceChunkDataStart+dataSize)>chainSpaceOffset;
		}
		
		private void setDataPos(long chunkOffset) throws IOException{
			data.setPos(chunk.getDataStart()+chunkOffset);
		}
		
		private boolean applyMovement(OverflowMode mode) throws IOException{
			var chunkOffset=chainSpaceOffset-chainSpaceDataStart;
			
			switch(mode){
			case SET -> {
				if(chunkOffset==0&&chainSpaceDataStart>0){
					var id=chunks.indexOf(chunk);
					setChunk(id-1);
					return applyMovement(mode);
				}
				
				chunk.setSize(chunkOffset);
				chunk.chainForwardFree();
			}
			case EXTEND -> {
				chunk.pushSize(chunkOffset);
				chunk.syncHeader();
			}
			}
			
			if(chunk==null) orientPointer(mode);
			
			
			if(!chunk.hasNext()){
				if(mode==CLIP){
					var overshoot=chunkOffset-chunk.getSize();
					if(overshoot>0){
						chunkOffset-=overshoot;
						chainSpaceOffset-=overshoot;
						
					}
				}
				setDataPos(chunkOffset);
				return true;
			}
			
			setDataPos(chunkOffset);
			
			var remaining=getDataSize(mode, chunk)-chunkOffset();
			return remaining>0;
		}
		
		private long chunkOffset(){
			return chainSpaceOffset-chainSpaceDataStart;
		}
		
		private void setPointer(long absolute, OverflowMode mode) throws IOException{
			if(absolute<0) throw new IndexOutOfBoundsException(absolute+"");
			
			chainSpaceOffset=absolute;
			orientPointer(mode);
		}
		
		private long getDataOffset(OverflowMode mode, int i) throws IOException{
			return mode==EOF?chunks.getChainSpaceSizeOffset(i):chunks.getChainSpaceCapacityOffset(i);
		}
		
		private long getDataSize(OverflowMode mode, Chunk chunk){
			return mode==EOF?chunk.getSize():chunk.getCapacity();
		}
		
		private boolean orientPointer(OverflowMode mode) throws IOException{
			
			//TODO: test if this optimization is a problem when chain is invalidated
			if(chunk!=null){
				checkUsed(chunk);
				
				if(isInRange(chainSpaceDataStart, getDataSize(mode, chunk))){
					if(applyMovement(mode)) return true;
				}
				invalidate();
			}
			
			int i=0;
			for(Chunk ch : chunks){
				checkUsed(ch);
				
				var off=getDataOffset(mode, i);
				var siz=getDataSize(mode, ch);
				if(isInRange(off, siz)){
					chunk=ch;
					chainSpaceDataStart=off;
					
					if(applyMovement(mode)) return true;
				}
				if(mode==SET){
					ch.setSize(ch.getCapacity());
					ch.syncHeader();
				}
				i++;
			}
			
			switch(mode){
			case CLIP -> {
				setChunk(chunks.size()-1);
			}
			case EXTEND, SET -> {
				setChunk(chunks.size()-1);
				var lastSize=chunk.getCapacity();
				var end     =chainSpaceDataStart+lastSize;
				var toGrow  =chainSpaceOffset-end;
				if(toGrow==0) return true;
				chunks.growBy(toGrow);
				orientPointer(mode);
				return true;
			}
			}
			
			return false;
		}
		
		@Override
		public RandomIO setPos(long pos) throws IOException{
			long[] old;
			if(DEBUG_VALIDATION){
				Assert(getSize() >= getPos());
				old=new long[]{getPos(), getSize(), pos};
			}
			
			setPointer(pos, CLIP);
			if(DEBUG_VALIDATION){
				Assert(getPos()==Math.min(getSize(), pos), old, getPos(), getSize(), pos, chunks);
			}
			return this;
		}
		
		@Override
		public long getPos(){
			return chainSpaceOffset;
		}
		
		@Override
		public long getSize() throws IOException{
			return chunks.getTotalSize();
		}
		
		@Override
		public RandomIO setSize(long targetSize) throws IOException{
			var oldSize=getSize();
			if(oldSize==targetSize) return this;
			Assert(targetSize<=getCapacity());
			
			long capacity;
			if(DEBUG_VALIDATION){
				capacity=getCapacity();
			}
			
			long remaining=targetSize;
			
			for(Chunk chunk : chunks){
				long consume=Math.min(remaining, chunk.getCapacity());
				remaining=Math.max(0, remaining-consume);
				
				chunk.setSize(consume);
				chunk.syncHeader();
			}
			
			invalidate();
			
			if(DEBUG_VALIDATION){
				Assert(targetSize==getSize(), "Capacity changed when setting size", targetSize+"/"+getSize(), capacity+"/"+getCapacity(), this);
				Assert(capacity==getCapacity(), capacity, getCapacity(), this);
			}
			
			return this;
		}
		
		@Override
		public long getCapacity() throws IOException{
			return chunks.getTotalCapacity();
		}
		
		@Override
		public RandomIO setCapacity(long targetCapacity) throws IOException{
			var oldCapacity=getCapacity();
			if(oldCapacity==targetCapacity) return this;
			
			long size;
			if(DEBUG_VALIDATION){
				size=getSize();
			}
			
			//clip size
			setSize(Math.min(getSize(), targetCapacity));
			
			long remaining=targetCapacity;
			for(Chunk chunk : chunks){
				remaining-=chunk.getCapacity();
				if(remaining<=0){
					chunk.chainForwardFree();
					break;
				}
			}
			
			if(remaining>0){
				chunks.growBy(remaining);
			}
			
			if(DEBUG_VALIDATION){
				Assert(targetCapacity<=getCapacity(), targetCapacity, getCapacity(), this);
				Assert(size==getSize()||targetCapacity==getSize(), "Size changed when setting capacity", targetCapacity+"/"+getCapacity(), size+"/"+getSize(), this);
			}
			
			return this;
		}
		
		private void finish(){
			finished=true;
			chunks.dependencyInvalidate.remove((Runnable)this::invalidate);
		}
		
		@Override
		protected void finalize() throws Throwable{
			super.finalize();
			if(!finished) LogUtil.printlnEr("DID NOT CLOSE STREAM");
			finish();
		}
		
		@Override
		public void close() throws IOException{
			finish();
			flush();
			if(chunk!=null) chunk.syncHeader();
			else{
				for(var c : chunks){
					c.syncHeader();
				}
			}
			data=null;
		}
		
		@Override
		public void flush() throws IOException{
			data.flush();
		}
		
		////////////////////////////////////////////////////////////////////////
		
		private int prepareRead(int requested) throws IOException{
			if(DEBUG_VALIDATION) Assert(requested>0);
			
			
			if(!orientPointer(EOF)){
				return -1;
			}
			
			var chunkOffset=chunkOffset();
			var remaining  =chunk.getSize()-chunkOffset;
			if(remaining==0){
				if(DEBUG_VALIDATION){
					Assert(!chunk.hasNext());
				}
				return -1;
			}
			return (int)Math.min(remaining, requested);
		}
		
		private void confirmRead(long bytesWritten){
			chainSpaceOffset+=bytesWritten;
		}
		
		private int prepareWrite(int requested, boolean pushUsed) throws IOException{
			if(DEBUG_VALIDATION) Assert(requested>0);
			
			orientPointer(pushUsed?EXTEND:CLIP);
			
			var chunkOffset=chunkOffset();
			var remaining  =chunk.getCapacity()-chunkOffset;
			
			if(remaining==0){
				chunks.growBy(requested);
				return prepareWrite(requested, pushUsed);
			}
			
			if(DEBUG_VALIDATION){
				Assert(remaining>0, requested, chunk, chunks);
			}
			return (int)Math.min(remaining, requested);
		}
		
		private void confirmWrite(long bytesWritten, boolean pushUsed) throws IOException{
			var chunkOffset=chunkOffset();
			chainSpaceOffset+=bytesWritten;
			
			if(pushUsed){
				var newSiz=chunkOffset+bytesWritten;
				
				chunk.pushSize(newSiz);
				chunk.syncHeader();
				
				if(DEBUG_VALIDATION){
					Assert(chunk.getSize() >= newSiz);
				}
			}
		}
		
		////////////////////////////////////////////////////////////////////////
		
		@Override
		public int read() throws IOException{
			var toRead=prepareRead(1);
			if(toRead==-1) return -1;
			
			Assert(toRead==1);
			int b=data.read();
			
			if(b==-1) return -1;
			confirmRead(1);
			
			return b;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			Objects.checkFromIndexSize(off, len, b.length);
			
			int total=0;
			while(len>0){
				var toRead=prepareRead(len);
				if(toRead==-1) return total==0?-1:total;
				
				Assert(toRead>0);
				int read=data.read(b, off, toRead);
				
				confirmRead(read);
				
				len-=read;
				off+=read;
				total+=read;
			}
			return total;
		}
		
		////////////////////////////////////////////////////////////////////////
		
		@Override
		public void write(int b) throws IOException{
			int toWrite=prepareWrite(1, true);
			Assert(toWrite==1);
			
			data.write(b);
			
			confirmWrite(1, true);
		}
		
		
		@Override
		public void write(byte[] b, int off, int len) throws IOException{
			write(b, off, len, true);
		}
		
		@Override
		public void fillZero(long requestedMemory) throws IOException{
			Utils.zeroFill((b, off, len)->write(b, off, len, false), requestedMemory);
		}
		
		@Override
		public long getGlobalPos() throws IOException{
			return data.getGlobalPos();
		}
		
		public void write(byte[] b, int off, int len, boolean pushUsed) throws IOException{
			Objects.checkFromIndexSize(off, len, b.length);
			
			while(len>0){
				int toWrite=prepareWrite(len, pushUsed);
				Assert(toWrite >= 1);
				
				data.write(b, off, toWrite);
				
				confirmWrite(toWrite, pushUsed);
				
				len-=toWrite;
				off+=toWrite;
			}
		}
		
		////////////////////////////////////////////////////////////////////////
		
		
		@Override
		public String toString(){
			return ChunkIO.this+" -> "+chainSpaceOffset;
		}
	}
	
	
	private final ChunkChain chunks;
	
	private final IOInterface chunkSource;
	
	public ChunkIO(Chunk chunk){
		chunkSource=chunk.header.source;
		chunks=new ChunkChain(chunk);
	}
	
	public Chunk getRoot(){ return chunks.get(0); }
	
	
	@Override
	public @com.lapissea.util.NotNull
	RandomIO doRandom() throws IOException{
		return new ChunkSpaceRandomIO(chunkSource.doRandom());
	}
	
	@Override
	public void setSize(long requestedSize) throws IOException{
		try(RandomIO io=doRandom()){
			long oldCapacity;
			if(DEBUG_VALIDATION){
				oldCapacity=io.getCapacity();
			}
			
			io.setSize(requestedSize);
			
			if(DEBUG_VALIDATION){
				Assert(io.getSize()==getSize(), "IO/source disagreement", io.getSize(), getSize());
				Assert(io.getCapacity()==getCapacity(), "IO/source disagreement", io.getCapacity(), getCapacity());
				
				Assert(io.getCapacity()==oldCapacity, "Capacity changed when setting size", io.getCapacity(), oldCapacity, io);
				Assert(io.getSize()==requestedSize, "Did not set requested size", io.getSize(), requestedSize, io);
			}
		}
	}
	
	
	@Override
	public long getSize(){
		//TODO: use precomputed implementation
		return chunks.stream().mapToLong(Chunk::getSize).sum();
	}
	
	@Override
	public long getCapacity(){
		//TODO: use precomputed implementation
		return chunks.stream().mapToLong(Chunk::getCapacity).sum();
	}
	
	@Override
	public String getName(){
		return chunkSource.getName()+"@"+getRoot().getOffset();
	}
	
	@Override
	public void setCapacity(long newCapacity) throws IOException{
		try(RandomIO io=doRandom()){
			long oldSize;
			if(DEBUG_VALIDATION) oldSize=io.getSize();
			
			io.setCapacity(newCapacity);
			
			if(DEBUG_VALIDATION){
				Assert(io.getSize()==getSize(), "IO/source disagreement", io.getSize(), getSize());
				Assert(io.getCapacity()==getCapacity(), "IO/source disagreement", io.getCapacity(), getCapacity());
				
				Assert(io.getCapacity() >= newCapacity, "Did not allocate enough", io.getCapacity(), newCapacity, io);
				Assert(oldSize==io.getSize()||
				       (io.getSize()==newCapacity&&newCapacity<oldSize),
				       "Size changed when setting capacity", oldSize, io.getSize(), newCapacity, io);
			}
		}
	}
	
	@Override
	public String toString(){
		return "ChunkIO{"+chunks+'}';
	}
}
