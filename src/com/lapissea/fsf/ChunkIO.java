package com.lapissea.fsf;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.fsf.ChunkIO.MoveMode.*;
import static com.lapissea.util.UtilL.*;

/**
 * Internal class that maps file IOInterface to read and expand (when needed) a chunk chain
 */
public class ChunkIO implements IOInterface{
	
	private final ChunkChain chunks;
	
	private final IOInterface chunkSource;
	
	public ChunkIO(Chunk chunk){
		chunkSource=chunk.header.source;
		chunks=new ChunkChain(chunk);
	}
	
	public Chunk getRoot(){
		return chunks.get(0);
	}
	
	
	protected enum MoveMode{
		CLIP,
		EOF,
		EXTEND,
		SET
	}
	
	@Override
	public RandomIO doRandom() throws IOException{
		
		class ChunkSpaceRandomIO implements RandomIO{
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
			}
			
			private void setChunk(int chainInd){
				chunk=chunks.get(chainInd);
				chainSpaceDataStart=chunks.getChainSpaceOffset(chainInd);
			}
			
			@Override
			public byte[] contentBuf(){
				return data.contentBuf();
			}
			
			private boolean isInRange(long chainSpaceChunkDataStart, long dataSize){
				return chainSpaceChunkDataStart<=chainSpaceOffset&&(chainSpaceChunkDataStart+dataSize)>chainSpaceOffset;
			}
			
			private boolean applyMovement(MoveMode mode) throws IOException{
				var chunkOffset=chainSpaceOffset-chainSpaceDataStart;
				
				switch(mode){
				case SET -> {
					if(chunkOffset==0&&chainSpaceDataStart>0){
						var id=chunks.indexOf(chunk);
						setChunk(id-1);
						return applyMovement(mode);
					}
					
					chunk.setUsed(chunkOffset);
					chunk.chainForwardFree();
				}
				case EXTEND -> {
					chunk.pushUsed(chunkOffset);
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
			
			private void setPointer(long absolute, MoveMode mode) throws IOException{
				if(absolute<0) throw new IndexOutOfBoundsException(absolute+"");
				
				chainSpaceOffset=absolute;
				orientPointer(mode);
			}
			
			private long getDataSize(MoveMode mode, Chunk chunk){
				return mode==EOF?chunk.getUsed():chunk.getDataSize();
			}
			
			private boolean orientPointer(MoveMode mode) throws IOException{
				
				//TODO: test if this optimization is a problem when chain is invalidated
				if(chunk!=null){
					if(isInRange(chainSpaceDataStart, getDataSize(mode, chunk))){
						if(applyMovement(mode)) return true;
					}
					invalidate();
				}
				
				int i=0;
				for(Chunk ch : chunks){
					var off=chunks.getChainSpaceOffset(i);
					if(isInRange(off, getDataSize(mode, ch))){
						chunk=ch;
						chainSpaceDataStart=off;
						
						if(applyMovement(mode)) return true;
					}
					if(mode==SET){
						ch.setUsed(ch.getDataSize());
					}
					i++;
				}
				
				switch(mode){
				case CLIP -> {
					setChunk(chunks.size()-1);
				}
				case EXTEND, SET -> {
					setChunk(chunks.size()-1);
					var lastSize=chunk.getDataSize();
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
			public RandomIO setCapacity(long newCapacity) throws IOException{
				var oldSize=getSize();
				if(oldSize==newCapacity) return this;
				
				
				var pos=getPos();
				
				if(newCapacity>oldSize){
					setPos(oldSize);
					Utils.zeroFill(this::write, newCapacity-oldSize);
				}else{
					setPointer(newCapacity, SET);
				}
				
				Assert(newCapacity==getSize());
				
				setPos(pos);
				
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
				data=null;
			}
			
			@Override
			public void flush() throws IOException{
				data.flush();
			}
			
			////////////////////////////////////////////////////////////////////////
			
			private int prepareRead(int requested) throws IOException{
				if(!orientPointer(EOF)){
					return -1;
				}
				
				var chunkOffset=chunkOffset();
				var remaining  =chunk.getUsed()-chunkOffset;
				if(remaining==0){
					Assert(!chunk.hasNext());
					return -1;
				}
				return (int)Math.min(remaining, requested);
			}
			
			private void confirmRead(long bytesWritten){
				chainSpaceOffset+=bytesWritten;
			}
			
			private int prepareWrite(int requested) throws IOException{
				orientPointer(EXTEND);
				
				var chunkOffset=chunkOffset();
				var remaining  =chunk.getDataSize()-chunkOffset;
				
				if(remaining==0){
					var oldOff=chainSpaceOffset;
					try{
						chainSpaceOffset+=requested;
						orientPointer(EXTEND);
					}finally{
						chainSpaceOffset=oldOff;
					}
					var result=prepareWrite(requested);
					Assert(result>0);
					return result;
				}
				return (int)Math.min(remaining, requested);
			}
			
			private void confirmWrite(long bytesWritten) throws IOException{
				var chunkOffset=chunkOffset();
				chainSpaceOffset+=bytesWritten;
				chunk.pushUsed(chunkOffset+bytesWritten);
				chunk.saveHeader();
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
				int toWrite=prepareWrite(1);
				Assert(toWrite==1);
				
				data.write(b);
				
				confirmWrite(1);
			}
			
			
			@Override
			public void write(byte[] b, int off, int len) throws IOException{
				Objects.checkFromIndexSize(off, len, b.length);
				
				while(len>0){
					int toWrite=prepareWrite(len);
					Assert(toWrite >= 1);
					
					data.write(b, off, toWrite);
					
					confirmWrite(toWrite);
					
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
		
		return new ChunkSpaceRandomIO(chunkSource.doRandom());
	}
	
	
	@Override
	public long getSize(){
		return chunks.stream().mapToLong(Chunk::getUsed).sum();
	}
	
	@Override
	public long getCapacity(){
		return chunks.stream().mapToLong(Chunk::getDataSize).sum();
	}
	
	@Override
	public void setCapacity(long newCapacity) throws IOException{
		try(var io=doRandom()){
			io.setCapacity(newCapacity);
		}
	}
	
	@Override
	public String toString(){
		return "ChunkIO{"+chunks+'}';
	}
}
