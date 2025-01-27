package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.exceptions.MalformedClusterData;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.FunctionOL;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public final class ChunkChainIO implements RandomIO{
	
	public final Chunk head;
	
	private Chunk cursor;
	private long  cursorStart;
	
	private long localPos;
	
	private final RandomIO source;
	
	public ChunkChainIO(Chunk head) throws IOException{
		this.head = head;
		if(DEBUG_VALIDATION){
			head.requireReal();
		}
		restartCursor();
		var prov = head.getDataProvider();
		var mem  = prov.getMemoryManager();
		if(mem != null) mem.notifyStart(this);
		
		source = prov.getSource().io();
	}
	
	public void revalidate() throws IOException{
		var pos = localPos;
		restartCursor();
		setCursor(pos);
	}
	
	private void restartCursor(){
		cursor = head;
		cursorStart = 0;
		localPos = 0;
	}
	
	private void checkCursorInChain() throws IOException{
		head.requireReal();
		
		if(head == cursor) return;
		
		cursor.requireReal();
		
		for(Chunk chunk : new ChainWalker(head)){
			if(chunk == cursor) return;
		}
		throw new MalformedClusterData(cursor + " not in " + head.collectNext());
	}
	
	private long calcCursorEnd(){
		return cursorStart + cursorEffectiveCapacity();
	}
	
	public long calcCursorOffset(){
		return localPos - cursorStart;
	}
	
	public long calcGlobalPos(){
		return calcGlobalPos(calcCursorOffset());
	}
	private long calcGlobalPos(long offset){
		return cursor.dataStart() + offset;
	}
	
	private long cursorEffectiveCapacity(){
		return effectiveCapacity(cursor);
	}
	private long effectiveCapacity(Chunk chunk){
		if(chunk.hasNextPtr()) return chunk.getCapacity();
		else return chunk.getSize();
	}
	
	/**
	 * @return flag if the cursor has advanced
	 */
	private boolean tryAdvanceCursor() throws IOException{
		var last = cursor;
		var next = cursor.next();
		if(next == null) return false;
		cursor = next;
		cursorStart += last.getSize();
		return true;
	}
	
	private void advanceCursorBy(long amount) throws IOException{
		setCursor(localPos + amount);
	}
	
	private void syncSourceCursor() throws IOException{
		source.setPos(calcGlobalPos());
	}
	
	private void syncSourceCursor(long cursorLocalOffset) throws IOException{
		source.setPos(calcGlobalPos(cursorLocalOffset));
	}
	
	private void setCursor(long pos) throws IOException{
		if(DEBUG_VALIDATION){
			checkCursorInChain();
		}
		try{
			if(pos<localPos){
				if(pos>=cursorStart){
					localPos = pos;
					return;
				}
				restartCursor();
			}
			
			while(true){
				long curserEnd = calcCursorEnd();
				if(curserEnd>pos){
					localPos = pos;
					return;
				}
				
				if(!tryAdvanceCursor()){//end reached
					localPos = curserEnd;
					return;
				}
			}
		}finally{
			if(DEBUG_VALIDATION){
				if(calcCursorOffset()<0) throw new AssertionError("cursorOffset " + calcCursorOffset() + " < 0");
				if(calcCursorOffset()>cursor.getSize()) throw new AssertionError("cursorOffset " + calcCursorOffset() + " > " + cursor.getSize());
				checkCursorInChain();
			}
		}
		
	}
	
	public Chunk getCursor(){
		return cursor;
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
		long sum = 0;
		
		Chunk chunk = start;
		while(chunk != null){
			sum += mapper.apply(chunk);
			chunk = chunk.next();
		}
		
		return sum;
	}
	
	@Override
	public long getCapacity() throws IOException{
		return mapSum(Chunk::getCapacity);
	}
	
	@Override
	public RandomIO setCapacity(long newCapacity) throws IOException{
		long prev = 0;
		
		Chunk chunk = Objects.requireNonNull(head);
		while(true){
			
			long contentStart = prev;
			long contentEnd   = contentStart + chunk.getSize();
			
			if(newCapacity>=contentStart && newCapacity<=contentEnd){
				
				long chunkSpace = newCapacity - contentStart;
				
				Chunk next = chunk.next();
				
				chunk.modifyAndSave(ch -> {
					ch.clampSize(chunkSpace);
					ch.clearNextPtr();
				});
				if(chunkSpace<chunk.getSize()){
					chunk.zeroOutFromTo(chunkSpace, chunk.getSize());
				}
				
				var cap = getCapacity();
				if(cap<newCapacity){
					throw new IOException("Capacity not allocated for: " + head.collectNext() + " " + cap + " < " + newCapacity);
				}
				
				if(next != null){
					try{
						next.freeChaining();
					}catch(FreeWhileUsed e){
						chunk.modifyAndSave(ch -> {
							try{
								ch.setNextPtr(next.getPtr());
							}catch(OutOfBitDepth ex){
								throw new RuntimeException(ex);
							}
						});
					}
					revalidate();
				}
				
				return this;
			}
			prev += chunk.getCapacity();
			if(!chunk.hasNextPtr()) break;
			chunk = Objects.requireNonNull(chunk.next());
		}
		
		long toGrow = newCapacity - prev;
		if(toGrow<=0){
			return this;
		}
		
		chunk.growBy(head, toGrow);
		
		//If grow has changed the header of cursor in a way that causes
		//an out of bounds for the cursor offset, then revalidate
		var offset = calcCursorOffset();
		if(offset<0 || offset>=cursor.getSize()){
			revalidate();
		}
		
		var cap = getCapacity();
		if(cap<newCapacity) throw new IOException(cap + " < " + newCapacity);
		return this;
	}
	@Override
	public void close() throws IOException{
		try{
			cursor.syncStruct();
		}finally{
			source.close();
		}
		var prov = head.getDataProvider();
		var mem  = prov.getMemoryManager();
		if(mem != null) mem.notifyEnd(this);
	}
	
	@Override
	public void flush() throws IOException{
		source.flush();
	}
	
	@Override
	public int read() throws IOException{
		long cOff      = calcCursorOffset();
		long remaining = cursor.getSize() - cOff;
		if(remaining == 0){
			if(tryAdvanceCursor()){
				return read();
			}
			return -1;
		}
		syncSourceCursor(cOff);
		int b = source.read();
		advanceCursorBy(1);
		return b;
	}
	@Override
	public int read(byte[] b, int off, int len) throws IOException{
		Objects.requireNonNull(b);
		if(len == 0) return 0;
		
		long cOff      = calcCursorOffset();
		long remaining = cursor.getSize() - cOff;
		if(remaining == 0){
			if(tryAdvanceCursor()){
				return read(b, off, len);
			}
			return -1;
		}
		
		int toRead = (int)Math.min(len, remaining);
		
		syncSourceCursor(cOff);
		int read = source.read(b, off, toRead);
		if(read>0){
			advanceCursorBy(read);
		}
		return read;
	}
	
	@Override
	public long readWord(int len) throws IOException{
		if(len == 0) return 0;
		
		long cursorOffset    = calcCursorOffset();
		long cursorRemaining = cursor.getSize() - cursorOffset;
		if(cursorRemaining>=len){
			syncSourceCursor(cursorOffset);
			var word = source.readWord(len);
			advanceCursorBy(len);
			return word;
		}
		
		return readMultiChunkWord(len);
	}
	
	public long readMultiChunkWord(int len) throws IOException{
		if(len == 0) return 0;
		
		long val = 0;
		
		var remaining = len;
		do{
			long cursorOffset    = calcCursorOffset();
			long cursorRemaining = cursor.getSize() - cursorOffset;
			if(cursorRemaining == 0) throw new EOFException();
			
			int toRead = (int)Math.min(remaining, cursorRemaining);
			syncSourceCursor(cursorOffset);
			val |= source.readWord(toRead)<<((len - remaining)*8);
			advanceCursorBy(toRead);
			remaining -= toRead;
		}while(remaining>0);
		
		return val;
	}
	
	
	private void ensureForwardCapacity(long amount) throws IOException{
		long cOff      = calcCursorOffset();
		long remaining = cursor.getCapacity() - cOff;
		
		if(amount<=remaining) return;
		
		if(DEBUG_VALIDATION){
			cursor.getDataProvider().checkCached(cursor);
			checkCursorInChain();
		}
		
		Chunk last = cursor;
		
		if(last.hasNextPtr()){
			for(var ch : last.requireNext().walkNext()){
				remaining += ch.getCapacity();
				if(amount<=remaining) return;
				last = ch;
			}
		}
		
		long toAllocate = amount - remaining;
		last.growBy(head, toAllocate);
		
		revalidate();
	}
	
	@Override
	public void write(int b) throws IOException{
		if(DEBUG_VALIDATION){
			checkCursorInChain();
		}
		
		long offset = calcCursorOffset();
		long cRem   = cursor.getCapacity() - offset;
		if(cRem>=1){
			cursor.pushSize(offset + 1);
			if(cursor.dirty()){
				var chunks = new ArrayList<WriteChunk>(2);
				writeHeadToBuf(chunks, cursor);
				chunks.add(new WriteChunk(calcGlobalPos(), 0, 1, new byte[]{(byte)b}));
				source.writeAtOffsets(chunks);
			}else{
				syncSourceCursor();
				source.write(b);
			}
			advanceCursorBy(1);
			return;
		}
		
		write(new byte[]{(byte)b}, 0, 1);
	}
	
	@Override
	public void write(byte[] b, int off, int len) throws IOException{
		if(len<=0) return;
		if(DEBUG_VALIDATION){
			checkCursorInChain();
		}
		
		while(true){
			long offset = calcCursorOffset();
			long cRem   = cursor.getCapacity() - offset;
			if(cRem>=len){
				cursor.pushSize(offset + len);
				if(cursor.dirty()){
					var chunks = new ArrayList<WriteChunk>(2);
					writeHeadToBuf(chunks, cursor);
					chunks.add(new WriteChunk(calcGlobalPos(), off, len, b));
					source.writeAtOffsets(chunks);
				}else{
					syncSourceCursor();
					source.write(b, off, len);
				}
				advanceCursorBy(len);
				return;
			}
			if(!cursor.hasNextPtr()){
				ensureForwardCapacity(len);
				if(!cursor.hasNextPtr()){
					continue;
				}
			}
			break;
		}
		var request = multiMappedWrite(b, off, len);
		if(request>0){
			ensureForwardCapacity(len);
			request = multiMappedWrite(b, off, len);
			if(request != 0) throw new IllegalStateException();
		}
	}
	
	private int multiMappedWrite(byte[] data, int off, int len) throws IOException{
		
		var offset          = calcCursorOffset();
		var cursorRemaining = cursor.getCapacity() - offset;
		int eventSize       = 2;
		{
			int remaining = len;
			remaining -= cursorRemaining;
			
			var ch = cursor;
			while(remaining>0){
				var next = ch.next();
				if(next == null){
					return remaining;
				}
				ch = next;
				remaining -= ch.getCapacity();
				eventSize += 2;
			}
		}
		
		List<WriteChunk> chunks = new ArrayList<>(eventSize);
		
		int dataOffset = off;
		int remaining  = len;
		
		WriteChunk cursorWrite;
		{
			var toWrite = (int)Math.min(remaining, cursorRemaining);
			cursorWrite = new WriteChunk(calcGlobalPos(), dataOffset, toWrite, data);
			remaining -= toWrite;
			dataOffset += toWrite;
			
			cursor.pushSize(offset + toWrite);
		}
		
		writeHeadToBuf(chunks, cursor);
		chunks.add(cursorWrite);
		
		var ch = cursor;
		while(remaining>0){
			ch = Objects.requireNonNull(ch.next());
			
			var toWrite = (int)Math.min(remaining, ch.getCapacity());
			var event   = new WriteChunk(ch.dataStart(), dataOffset, toWrite, data);
			remaining -= toWrite;
			dataOffset += toWrite;
			
			ch.pushSize(toWrite);
			writeHeadToBuf(chunks, ch);
			chunks.add(event);
		}
		
		chunks.sort(Comparator.naturalOrder());
		
		source.writeAtOffsets(chunks);
		advanceCursorBy(len);
		
		return 0;
	}
	
	private void writeHeadToBuf(List<WriteChunk> dest, Chunk chunk) throws IOException{
		if(!chunk.dirty()) return;
		dest.add(chunk.writeHeaderToBuf());
	}
	
	@Override
	public void writeWord(long v, int len) throws IOException{
		long offset = calcCursorOffset();
		long cRem   = cursor.getCapacity() - offset;
		
		if(cRem<len){
			RandomIO.super.writeWord(v, len);
			return;
		}
		
		syncSourceCursor(offset);
		source.writeWord(v, len);
		cursor.modifyAndSave(c -> c.pushSize(offset + len));
		advanceCursorBy(len);
	}
	
	@Override
	public void fillZero(long requestedMemory) throws IOException{
		long  remaining = requestedMemory;
		Chunk chunk     = cursor;
		{
			long offset  = calcCursorOffset();
			long cRem    = chunk.getSize() - offset;
			int  toWrite = (int)Math.min(remaining, cRem);
			source.setPos(chunk.dataStart() + offset).fillZero(toWrite);
			remaining -= toWrite;
			chunk = chunk.next();
		}
		
		while(remaining>0 && chunk != null){
			long cRem = chunk.getSize();
			if(cRem == 0 && !chunk.hasNextPtr()){
				return;
			}
			
			int toWrite = (int)Math.min(remaining, cRem);
			source.setPos(chunk.dataStart()).fillZero(toWrite);
			
			remaining -= toWrite;
			chunk = chunk.next();
		}
		syncSourceCursor();
	}
	@Override
	public boolean isReadOnly(){
		return head.isReadOnly();
	}
	
	@Override
	public void writeAtOffsets(Collection<WriteChunk> data) throws IOException{
		long requiredCapacity = 0;
		for(var d : data){
			var ioEnd = d.ioEnd();
			if(requiredCapacity<ioEnd) requiredCapacity = ioEnd;
		}
		if(requiredCapacity == 0) return;
		ensureCapacity(requiredCapacity);
		
		var chunks = head.collectNext();
		
		long[] localRanges = new long[chunks.size()*2];
		
		var offset = 0L;
		for(int i = 0; i<chunks.size(); i++){
			var chunk = chunks.get(i);
			var cap   = chunk.getCapacity();
			localRanges[i*2] = offset;
			localRanges[i*2 + 1] = offset + cap;
			offset += cap;
		}
		
		var mappedChunks = new ArrayList<WriteChunk>((int)(data.size()*1.1));
		
		var iter = data.iterator();
		var redo = new LinkedList<WriteChunk>();
		
		int pushIndex = 0;
		
		while(iter.hasNext() || !redo.isEmpty()){
			WriteChunk unmapped;
			if(!redo.isEmpty()) unmapped = redo.removeFirst();
			else{
				unmapped = iter.next();
			}
			
			var eStart = unmapped.ioOffset();
			
			int index = findIndex(localRanges, eStart);
			
			var start       = localRanges[index*2];
			var end         = localRanges[index*2 + 1];
			var size        = end - start;
			var chunkOffset = eStart - start;
			
			var remaining = size - chunkOffset;
			
			
			var chunk = chunks.get(index);
			
			if(remaining<unmapped.dataLength()){
				var split = unmapped.split(Math.toIntExact(remaining));
				unmapped = split.before();
				redo.add(split.after());
			}
			
			var w = unmapped.withOffset(chunk.dataStart() + chunkOffset);
			
			mappedChunks.add(w);
			if(pushIndex<index){
				for(int j = pushIndex; j<index; j++){
					var c = chunks.get(j);
					c.setSize(c.getCapacity());
				}
				pushIndex = index;
			}
			chunk.pushSize(chunkOffset + unmapped.dataLength());
		}
		
		for(Chunk chunk : chunks){
			if(!chunk.dirty()) continue;
			var d = chunk.writeHeaderToBuf();
			UtilL.addRemainSorted(mappedChunks, d);
		}
		
		source.writeAtOffsets(mappedChunks);
	}
	
	private int findIndex(long[] localRanges, long writeStart){
		for(int i = 0; i<localRanges.length/2; i++){
			var start = localRanges[i*2];
			if(writeStart>=start){
				var end         = localRanges[i*2 + 1];
				var size        = end - start;
				var chunkOffset = writeStart - start;
				if(chunkOffset>=size){
					continue;
				}
				return i;
			}
		}
		throw new ShouldNeverHappenError();
	}
	
	@Override
	public void setSize(long targetSize) throws IOException{
		long remaining = targetSize;
		
		if(DEBUG_VALIDATION){
			if(getCapacity()<targetSize) throw new IllegalArgumentException(getCapacity() + ">=" + targetSize);
		}
		
		Chunk chunk = head;
		while(true){
			if(remaining<chunk.getSize()){
				chunk.clampSize(remaining);
			}else{
				chunk.growSizeAndZeroOut(Math.min(chunk.getCapacity(), remaining));
			}
			chunk.syncStruct();
			
			var newSize = chunk.getSize();
			remaining = Math.max(0, remaining - newSize);
			
			var next = chunk.next();
			if(next == null){
				break;
			}
			chunk = next;
		}
		
		if(remaining>0){
			var siz    = chunk.getSize();
			var cap    = chunk.getCapacity();
			var newSiz = siz + remaining;
			if(cap<newSiz) throw new IOException("size too big! " + cap + " " + newSiz);
			
			chunk.modifyAndSave(c -> c.setSize(newSiz));
		}
		
		if(DEBUG_VALIDATION){
			if(targetSize != getSize()) throw new IllegalStateException(targetSize + "!=" + getSize());
		}
	}
	
	@Override
	public boolean inTransaction(){
		return source.inTransaction();
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
			siz = getSize() + "";
		}catch(IOException e){
			siz = "?";
		}
		String cap;
		try{
			cap = getCapacity() + "";
		}catch(IOException e){
			cap = "?";
		}
		return this.getClass().getSimpleName() + "{@" + getPos() + " / " + siz + "(" + cap + "), pos=" + cursor.getPtr() + "+" + calcCursorOffset() + "}";
	}
}
