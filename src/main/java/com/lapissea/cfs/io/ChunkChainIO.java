package com.lapissea.cfs.io;

import com.lapissea.cfs.chunk.ChainWalker;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.FunctionOL;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class ChunkChainIO implements RandomIO{
	
	public final Chunk head;
	
	private Chunk cursor;
	private long  cursorStart;
	
	private long localPos;
	
	private final RandomIO source;
	
	public ChunkChainIO(Chunk head) throws IOException{
		this.head=head;
		restartCursor();
		source=head.getDataProvider().getSource().io();
	}
	
	public void revalidate() throws IOException{
		var pos=localPos;
		restartCursor();
		setCursor(pos);
	}
	
	private void restartCursor(){
		cursor=head;
		cursorStart=0;
		localPos=0;
	}
	
	private void checkCursorInChain() throws IOException{
		if(DEBUG_VALIDATION){
			head.requireReal();
			cursor.requireReal();
		}
		if(head==cursor) return;
		assert head.streamNext().anyMatch(c->c==cursor):cursor+" not in "+head.collectNext();
	}
	
	private long calcCursorEnd(){
		return cursorStart+cursorEffectiveCapacity();
	}
	
	public long calcCursorOffset(){
		return localPos-cursorStart;
	}
	
	public long calcGlobalPos(){
		return calcGlobalPos(calcCursorOffset());
	}
	private long calcGlobalPos(long offset){
		return cursor.dataStart()+offset;
	}
	
	private long cursorEffectiveCapacity(){
		return effectiveCapacity(cursor);
	}
	private long effectiveCapacity(Chunk chunk){
		if(chunk.hasNextPtr()) return chunk.getCapacity();
		else return chunk.getSize();
	}
	
	/**
	 * @return flag if cursor has advanced
	 */
	private boolean tryAdvanceCursor() throws IOException{
		var last=cursor;
		var next=cursor.next();
		if(next==null) return false;
		cursor=next;
		cursorStart+=last.getSize();
		return true;
	}
	
	private void advanceCursorBy(long amount) throws IOException{
		setCursor(localPos+amount);
	}
	
	private RandomIO syncedSource() throws IOException{
		syncSourceCursor();
		return source;
	}
	
	private void syncSourceCursor() throws IOException{
		source.setPos(calcGlobalPos());
	}
	
	private void setCursor(long pos) throws IOException{
		if(DEBUG_VALIDATION){
			checkCursorInChain();
		}
		try{
			if(pos<localPos){
				if(pos>=cursorStart){
					localPos=pos;
					return;
				}
				restartCursor();
			}
			
			while(true){
				long curserEnd=calcCursorEnd();
				if(curserEnd>pos){
					localPos=pos;
					return;
				}
				
				if(!tryAdvanceCursor()){//end reached
					localPos=curserEnd;
					return;
				}
			}
		}finally{
			assert calcCursorOffset()>=0:"cursorOffset "+calcCursorOffset()+" < 0";
			assert calcCursorOffset()<=cursor.getSize():"cursorOffset "+calcCursorOffset()+" > "+cursor.getSize();
			checkCursorInChain();
		}
		
	}
	
	public Chunk getCursor(){
		return cursor;
	}
	
	@Override
	public long getPos(){return localPos;}
	
	@Override
	public RandomIO setPos(long pos) throws IOException{
		setCursor(pos);
		return this;
	}
	
	private long mapSum(FunctionOL<Chunk> mapper) throws IOException{
		return mapSum(head, mapper);
	}
	private long mapSum(Chunk start, FunctionOL<Chunk> mapper) throws IOException{
		long sum=0;
		
		Chunk chunk=start;
		while(chunk!=null){
			sum+=mapper.apply(chunk);
			chunk=chunk.next();
		}
		
		return sum;
	}
	
	@Override
	public long getCapacity() throws IOException{
		return mapSum(Chunk::getCapacity);
	}
	
	@Override
	public RandomIO setCapacity(long newCapacity) throws IOException{
		long prev=0;
		
		Chunk chunk=Objects.requireNonNull(head);
		while(true){
			
			long contentStart=prev;
			long contentEnd  =contentStart+chunk.getSize();
			
			if(newCapacity>=contentStart&&newCapacity<=contentEnd){
				
				long chunkSpace=newCapacity-contentStart;
				
				Chunk next=chunk.next();
				
				chunk.modifyAndSave(ch->{
					ch.clampSize(chunkSpace);
					ch.clearNextPtr();
				});
				if(chunkSpace<chunk.getSize()){
					chunk.zeroOutFromTo(chunkSpace, chunk.getSize());
				}
				
				if(next!=null){
					next.freeChaining();
					revalidate();
				}
				
				var cap=getCapacity();
				if(cap<newCapacity) throw new IOException(cap+" < "+newCapacity);
				
				return this;
			}
			prev+=chunk.getCapacity();
			if(!chunk.hasNextPtr()) break;
			chunk=Objects.requireNonNull(chunk.next());
		}
		
		long toGrow=newCapacity-prev;
		
		chunk.growBy(head, toGrow);
		
		var cap=getCapacity();
		if(cap<newCapacity) throw new IOException(cap+" < "+newCapacity);
		return this;
	}
	@Override
	public void close() throws IOException{
		cursor.syncStruct();
		source.close();
	}
	
	@Override
	public void flush() throws IOException{
		source.flush();
	}
	
	@Override
	public int read() throws IOException{
		if(remaining()<=0) return -1;
		int b=syncedSource().read();
		advanceCursorBy(1);
		return b;
	}
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		Objects.requireNonNull(b);
		if(len==0) return 0;
		
		long cOff     =calcCursorOffset();
		long remaining=cursor.getSize()-cOff;
		if(remaining==0) return -1;
		
		int toRead=(int)Math.min(len, remaining);
		
		int read=syncedSource().read(b, off, toRead);
		if(read>0){
			advanceCursorBy(read);
		}
		return read;
	}
	
	private void ensureForwardCapacity(long amount) throws IOException{
		long cOff     =calcCursorOffset();
		long remaining=cursor.getCapacity()-cOff;
		
		if(amount<=remaining) return;
		
		if(DEBUG_VALIDATION){
			cursor.getDataProvider().checkCached(cursor);
			checkCursorInChain();
		}
		
		Chunk last =cursor;
		Chunk chunk=last.next();
		
		if(chunk!=null){
			for(Chunk c : new ChainWalker(chunk)){
				remaining+=chunk.getCapacity();
				if(amount<=remaining) return;
				last=c;
			}
		}
		
		long toAllocate=amount-remaining;
		last.growBy(head, toAllocate);
		
	}
	
	@Override
	public void write(int b) throws IOException{
		write(new byte[]{(byte)b}, 0, 1);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		if(len<=0) return;
		if(DEBUG_VALIDATION){
			checkCursorInChain();
		}
		
		int remaining=len;
		int offAdd   =off;
		
		while(remaining>0){
			long offset=calcCursorOffset();
			long cRem  =cursor.getCapacity()-offset;
			if(cRem<remaining&&!cursor.hasNextPtr()){
				ensureForwardCapacity(remaining);
				continue;
			}
			int toWrite=(int)Math.min(remaining, cRem);
			
			syncedSource().write(b, offAdd, toWrite);
			cursor.modifyAndSave(c->{
				try{
					c.pushSize(offset+toWrite);
				}catch(BitDepthOutOfSpaceException e){
					/*
					 * size can not exceed capacity. If it somehow does and capacity
					 * did not throw bitdepth then this should not be the biggest concern.
					 * */
					throw new ShouldNeverHappenError(e);
				}
			});
			advanceCursorBy(toWrite);
			
			offAdd+=toWrite;
			remaining-=toWrite;
		}
	}
	@Override
	public void fillZero(long requestedMemory) throws IOException{
		long  remaining=requestedMemory;
		Chunk chunk    =cursor;
		{
			long offset =calcCursorOffset();
			long cRem   =chunk.getSize()-offset;
			int  toWrite=(int)Math.min(remaining, cRem);
			source.setPos(chunk.dataStart()+offset).fillZero(toWrite);
			remaining-=toWrite;
			chunk=chunk.next();
		}
		
		while(remaining>0&&chunk!=null){
			long cRem=chunk.getSize();
			if(cRem==0&&!chunk.hasNextPtr()){
				return;
			}
			
			int toWrite=(int)Math.min(remaining, cRem);
			source.setPos(chunk.dataStart()).fillZero(toWrite);
			
			remaining-=toWrite;
			chunk=chunk.next();
		}
		syncSourceCursor();
	}
	@Override
	public boolean isReadOnly(){
		return head.isReadOnly();
	}
	
	@Override
	public void setSize(long targetSize) throws IOException{
		long remaining=targetSize;
		
		assert getCapacity()>=targetSize:getCapacity()+">="+targetSize;
		
		Chunk chunk=head;
		while(true){
			if(remaining<chunk.getSize()){
				chunk.clampSize(remaining);
			}else{
				chunk.growSizeAndZeroOut(Math.min(chunk.getCapacity(), remaining));
			}
			chunk.syncStruct();
			
			var newSize=chunk.getSize();
			remaining=Math.max(0, remaining-newSize);
			
			var next=chunk.next();
			if(next==null){
				break;
			}
			chunk=next;
		}
		
		if(remaining>0){
			var siz   =chunk.getSize();
			var cap   =chunk.getCapacity();
			var newSiz=siz+remaining;
			if(cap<newSiz) throw new IOException("size too big! "+cap+" "+newSiz);
			
			try{
				chunk.setSize(newSiz);
			}catch(BitDepthOutOfSpaceException e){
				//capacity ensures bitspace is large enough
				throw new ShouldNeverHappenError();
			}
			chunk.syncStruct();
		}
		
		assert targetSize==getSize():targetSize+"!="+getSize();
	}
	
	@Override
	public long getSize() throws IOException{
		return mapSum(Chunk::getSize);
	}
	@Override
	public boolean isDirect(){
		return true;
	}
	@Override
	public String toString(){
		String siz;
		try{
			siz=getSize()+"";
		}catch(IOException e){
			siz="?";
		}
		String cap;
		try{
			cap=getCapacity()+"";
		}catch(IOException e){
			cap="?";
		}
		return this.getClass().getSimpleName()+"{@"+getPos()+" / "+siz+"("+cap+"), pos="+cursor.getPtr()+"+"+calcCursorOffset()+"}";
	}
}
