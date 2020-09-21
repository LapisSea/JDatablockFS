package com.lapissea.cfs.objects.chunk;

import com.lapissea.cfs.io.RandomIO;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.FunctionOL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.cfs.Config.*;

public class ChunkIO implements RandomIO{
	
	public final Chunk head;
	
	private Chunk cursor;
	private long  cursorStart;
	
	private long localPos;
	
	private final RandomIO source;
	
	public ChunkIO(Chunk head) throws IOException{
		this.head=head;
		restartCursor();
		source=head.cluster.getData().io();
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
	
	private long calcCursorEnd(){
		return cursorStart+cursorEffectiveCapacity();
	}
	
	private long calcCursorOffset(){
		return localPos-cursorStart;
	}
	
	private long calcGlobalPos(){
		return calcGlobalPos(calcCursorOffset());
	}
	private long calcGlobalPos(long offset){
		return cursor.dataStart()+offset;
	}
	
	private long cursorEffectiveCapacity(){
		return effectiveCapacity(cursor);
	}
	private long effectiveCapacity(Chunk chunk){
		if(chunk.hasNext()) return chunk.getCapacity();
		else return chunk.getSize();
	}
	
	private record Range(long from, long to){
		Range{
			assert from<=to:from+" "+to;
		}
		boolean isEmpty(){return from==to;}
		public long size(){
			return to-from;
		}
	}
	
	private List<Range> localToGlobal(long size, FunctionOL<Chunk> mapper) throws IOException{
		return localToGlobal(cursor, calcCursorOffset(), size, mapper);
	}
	
	private List<Range> localToGlobal(Chunk start, long startOffset, long size, FunctionOL<Chunk> mapper) throws IOException{
		if(size==0) return List.of();
		
		long remaining=size;
		
		List<Range> result=new ArrayList<>(5);
		{
			var startPos=start.dataStart()+startOffset;
			
			long siz=mapper.apply(start)-startOffset;
			
			if(siz>=remaining) return List.of(new Range(startPos, startPos+remaining));
			
			result.add(new Range(startPos, startPos+siz));
			remaining-=siz;
		}
		
		Chunk chunk=start;
		while(remaining>0&&(chunk=chunk.next())!=null){
			
			long siz=Math.min(mapper.apply(chunk), remaining);
			
			long startPos=chunk.dataStart();
			
			result.add(new Range(startPos, startPos+siz));
			remaining-=siz;
		}
		
		return result;
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
		}
		
	}
	
	
	@Override
	public long getPos(){ return localPos; }
	
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
					ch.setNextPtr(null);
				});
				if(chunkSpace<chunk.getSize()){
					chunk.zeroOutFromTo(chunkSpace);
				}
				
				if(next!=null){
					next.freeChaining();
				}
				
				var cap=getCapacity();
				if(cap<newCapacity) throw new IOException(cap+" < "+newCapacity);
				
				return this;
			}
			prev+=chunk.getCapacity();
			if(!chunk.hasNext()) break;
			chunk=Objects.requireNonNull(chunk.next());
		}
		
		long toGrow=newCapacity-prev;
		
		chunk.growBy(toGrow);
		
		var cap=getCapacity();
		if(cap<newCapacity) throw new IOException(cap+" < "+newCapacity);
		return this;
	}
	@Override
	public void close() throws IOException{
		cursor.syncStruct();
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
		
		long   cOff     =calcCursorOffset();
		long   remaining=cursor.getSize()-cOff;
		long[] next     ={cursor.dataEnd(), cursor.dataStart(), cursor.getPtr().value()};
		if(remaining==0) return -1;
		
		int toRead=(int)Math.min(len, remaining);

//		if(toRead==1){
//			LogUtil.println();
//			int byt=read();
//			assert byt>=0:byt;
//
//			b[off]=(byte)byt;
//			return 1;
//		}
		
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
			cursor.cluster.checkCached(cursor);
		}
		
		Chunk last =cursor;
		Chunk chunk=last.next();
		
		if(chunk!=null){
			for(Chunk c : chunk){
				remaining+=chunk.getCapacity();
				if(amount<=remaining) return;
				last=c;
			}
		}
		
		long toAllocate=amount-remaining;
		last.growBy(toAllocate);
		
	}
	
	@Override
	public void write(int b) throws IOException{
		write(new byte[]{(byte)b}, 0, 1);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		if(len<=0) return;
		
		int remaining=len;
		int offAdd   =off;
		
		while(remaining>0){
			long offset=calcCursorOffset();
			long cRem  =cursor.getCapacity()-offset;
			if(cRem==0&&!cursor.hasNext()){
				ensureForwardCapacity(remaining);
				continue;
			}
			int toWrite=(int)Math.min(remaining, cRem);
			
			syncedSource().write(b, offAdd, toWrite);
			cursor.modifyAndSave(c->c.pushSize(offset+toWrite));
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
			if(cRem==0&&!chunk.hasNext()){
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
	public long getGlobalPos(){
		return calcGlobalPos();
	}
	
	@Override
	public void setSize(long targetSize) throws IOException{
		long remaining=targetSize;
		
		Chunk chunk=head;
		do{
			long oldSize=chunk.getSize();
			
			long newSize=Math.min(remaining, oldSize);

//			if(newSize<chunk.getSize()){
//				chunk.zeroOutFromTo(newSize);
//			}
			
			chunk.modifyAndSave(c->c.setSize(newSize));
			
			remaining=Math.max(0, remaining-newSize);
		}while((chunk=chunk.next())!=null);
		
		assert targetSize==getSize():targetSize+"!="+getSize();
	}
	
	@Override
	public long getSize() throws IOException{
		return mapSum(Chunk::getSize);
	}
	
	@Override
	public String toString(){
		return TextUtil.toNamedJson(this);
	}
}
