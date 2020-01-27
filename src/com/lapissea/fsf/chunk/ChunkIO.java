package com.lapissea.fsf.chunk;


import com.lapissea.fsf.io.IOInterface;
import com.lapissea.fsf.io.RandomIO;
import com.lapissea.fsf.Utils;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.fsf.chunk.ChunkIO.OverflowMode.*;
import static com.lapissea.fsf.FileSystemInFile.*;
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
		
		
		ChunkSpaceRandomIO(RandomIO data){
			this.data=data;
			chunks.dependencyInvalidate.add(this::invalidate);
		}
		
		private void invalidate(){
			chunk=null;
			chainSpaceDataStart=0;
			
			if(DEBUG_VALIDATION){
				Assert(chunks.stream().allMatch(Chunk::isUsed), chunks);
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
			
			data.setPos(chunk.getDataStart()+chunkOffset);
			
			if(!chunk.hasNext()) return true;
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
				if(DEBUG_VALIDATION){
					Assert(chunk.isUsed(), chunk, chunks);
				}
				if(isInRange(chainSpaceDataStart, getDataSize(mode, chunk))){
					if(applyMovement(mode)) return true;
				}
				invalidate();
			}
			
			int i=0;
			for(Chunk ch : chunks){
				if(DEBUG_VALIDATION){
					Assert(ch.isUsed(), ch, chunks);
				}
				
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
			setPointer(pos, CLIP);
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
			
			var pos=getPos();
			
			if(targetCapacity>oldCapacity){
				setPointer(oldCapacity, CLIP);
				fillZero(targetCapacity-oldCapacity);
			}else{
				setPointer(targetCapacity, SET);
			}
			setPos(pos);
			
			
			if(DEBUG_VALIDATION){
				Assert(targetCapacity<=getCapacity(), targetCapacity, getCapacity(), this);
				Assert(size==getSize()||targetCapacity==getSize(), "Size changed when setting capacity", targetCapacity+"/"+getCapacity(), size+"/"+getSize(), this);
			}
			
			return this;
		}
		
		private void finish(){
			chunks.dependencyInvalidate.remove((Runnable)this::invalidate);
		}
		
		@Override
		protected void finalize() throws Throwable{
			super.finalize();
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
	public RandomIO doRandom() throws IOException{
		return new ChunkSpaceRandomIO(chunkSource.doRandom());
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
	public void setCapacity(long newCapacity) throws IOException{
		try(var io=doRandom()){
			long oldSize;
			if(DEBUG_VALIDATION) oldSize=io.getSize();
			
			io.setCapacity(newCapacity);
			
			if(DEBUG_VALIDATION){
				Assert(io.getCapacity() >= newCapacity,
				       io.getCapacity(), newCapacity, io);
				Assert(oldSize==io.getSize()||io.getSize()==newCapacity,
				       oldSize, io.getSize(), newCapacity, io);
			}
		}
	}
	
	@Override
	public String toString(){
		return "ChunkIO{"+chunks+'}';
	}
}
