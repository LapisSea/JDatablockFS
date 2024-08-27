package com.lapissea.dfs.io;

import com.lapissea.dfs.io.bit.BitUtils;
import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.util.UtilL;

import java.io.EOFException;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.PrimitiveIterator;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

/**
 * This class provides the logic for {@link IOTransaction} by buffering data changes in memory as a sorted
 * list of {@link WriteEvent}. When the transaction accumulates a large number of events, it attempts to
 * merge them to reduce the overhead of writing new changes, but at the expense of writing more data when
 * it is exported upon closing.
 */
public final class IOTransactionBuffer{
	
	public interface BaseAccess{
		int read(long offset, byte[] b, int off, int len) throws IOException;
	}
	
	public record TransactionExport(OptionalPP<Long> setCapacity, List<RandomIO.WriteChunk> writes) implements Serializable{
		
		public void apply(RandomIO io) throws IOException{
			switch(writes.size()){
				case 0 -> { }
				case 1 -> {
					var e = writes.getFirst();
					io.setPos(e.ioOffset()).write(e.data(), e.dataOffset(), e.dataLength());
				}
				default -> io.writeAtOffsets(writes);
			}
			if(setCapacity.isPresent()){
				io.setCapacity(setCapacity.get());
			}
		}
		
	}
	
	private abstract static sealed class WriteEvent implements Comparable<WriteEvent>{
		final long offset;
		private WriteEvent(long offset){
			this.offset = offset;
			if(offset<0){
				throw new IllegalArgumentException("offset < 0");
			}
		}
		
		abstract int length();
		
		long start(){ return offset; }
		long end()  { return offset + length(); }
		
		@Override
		public int compareTo(WriteEvent o){
			return Long.compare(offset, o.offset);
		}
		
		abstract int read1(int off);
		abstract long asWord();
		abstract long readWord(int off, int len);
		abstract void writeTo(byte[] dest, int off, int len);
		abstract void writeTo(int localOff, byte[] dest, int off, int len);
		abstract WriteEvent trim(int newLen);
		abstract WriteEvent trimStart(int newLen);
		abstract boolean tryInlineSet(int localOff, long word, int len);
		abstract boolean tryInlineSet(int localOff, byte[] data, int off, int len);
		abstract WriteEvent append(byte[] b, int off, int len);
		abstract WriteEvent append(WriteEvent other);
		abstract byte[] byteArr();
		abstract WriteEvent prepend(byte[] b, int off, int len);
		
		static final class Word extends WriteEvent{
			private long data;
			private byte len;
			
			public Word(long offset, long data, int len){ this(offset, data, (byte)len); }
			public Word(long offset, long data, byte len){
				super(offset);
				if(len>8) throw new IllegalArgumentException();
				this.data = data;
				this.len = len;
			}
			
			@Override
			public int length(){ return len; }
			@Override
			public int read1(int off){ return (int)(shifted(off)&0xFF); }
			@Override
			long asWord(){ return data; }
			@Override
			public long readWord(int off, int len){ return shifted(off)&mask(len); }
			private long shifted(int off){ return data >>> (off*Byte.SIZE); }
			@Override
			public void writeTo(byte[] dest, int off, int len){ WordIO.setWord(data, dest, off, len); }
			@Override
			public void writeTo(int localOff, byte[] dest, int off, int len){ WordIO.setWord(shifted(localOff), dest, off, len); }
			@Override
			public WriteEvent trim(int newLen){
				if(len<newLen) throw new IllegalArgumentException();
				return new Word(offset, data&mask(newLen), newLen);
			}
			@Override
			public WriteEvent trimStart(int newLen){
				if(len<newLen) throw new IllegalArgumentException();
				var copyOff = len - newLen;
				var newOff  = offset + copyOff;
				return new Word(newOff, shifted(copyOff), newLen);
			}
			private static long mask(int bytes){ return BitUtils.makeMask(bytes*Byte.SIZE); }
			@Override
			boolean tryInlineSet(int localOff, long word, int len){
				var newLength = localOff + len;
				if(newLength>8) return false;
				doSet(localOff, word, len, (byte)newLength);
				return true;
			}
			@Override
			boolean tryInlineSet(int localOff, byte[] data, int off, int len){
				var newLength = localOff + len;
				if(newLength>8) return false;
				doSet(localOff, WordIO.getWord(data, off, len), len, (byte)newLength);
				return true;
			}
			private void doSet(int localOff, long word, int wLen, byte newLength){
				var mask = mask(wLen);
				var offb = (localOff*Byte.SIZE);
				data = (data&(~(mask<<offb)))|
				       ((word&mask)<<offb);
				if(len<newLength){
					len = newLength;
				}
			}
			@Override
			WriteEvent append(byte[] b, int off, int len){
				var newLen = this.len + len;
				if(newLen<=8){
					var word = WordIO.getWord(b, off, len);
					data |= word<<(this.len*Byte.SIZE);
					this.len = (byte)newLen;
					return this;
				}
				var bb = new byte[newLen];
				WordIO.setWord(data, bb, 0, this.len);
				System.arraycopy(b, off, bb, this.len, len);
				return new Arr(offset, bb);
			}
			@Override
			WriteEvent append(WriteEvent other){
				var newLen = this.len + other.length();
				if(newLen<=8){
					var word = other.asWord();
					data |= word<<(this.len*Byte.SIZE);
					this.len = (byte)newLen;
					return this;
				}
				var bb = new byte[newLen];
				WordIO.setWord(data, bb, 0, this.len);
				other.writeTo(bb, this.len, other.length());
				return new Arr(offset, bb);
			}
			@Override
			byte[] byteArr(){
				byte[] arr = new byte[len];
				WordIO.setWord(data, arr, 0, len);
				return arr;
			}
			@Override
			WriteEvent prepend(byte[] b, int off, int len){
				var newLen = this.len + len;
				if(newLen<=8){
					var word = WordIO.getWord(b, off, len);
					return new Word(offset - len, word|(data<<(len*Byte.SIZE)), newLen);
				}
				var bb = new byte[newLen];
				System.arraycopy(b, off, bb, 0, len);
				WordIO.setWord(data, bb, len, this.len);
				return new Arr(offset - len, bb);
			}
			@Override
			public String toString(){ return "W" + start() + " - " + end() + " " + Arrays.toString(byteArr()); }
		}
		
		static final class Arr extends WriteEvent{
			private byte[] data;
			
			public Arr(long offset, byte[] data){
				super(offset);
				this.data = data;
			}
			@Override
			public int length(){ return data.length; }
			@Override
			public int read1(int off){ return Byte.toUnsignedInt(data[off]); }
			@Override
			long asWord(){
				if(data.length>8) throw new UnsupportedOperationException();
				return WordIO.getWord(data, 0, data.length);
			}
			@Override
			public long readWord(int off, int len){ return WordIO.getWord(data, off, len); }
			@Override
			public void writeTo(byte[] dest, int off, int len){ System.arraycopy(data, 0, dest, off, len); }
			@Override
			public void writeTo(int localOff, byte[] dest, int off, int len){ System.arraycopy(data, localOff, dest, off, len); }
			@Override
			public WriteEvent trim(int newLen){
				if(newLen<=8) return new Word(offset, WordIO.getWord(data, 0, newLen), newLen);
				return new Arr(offset, Arrays.copyOf(data, newLen));
			}
			@Override
			public WriteEvent trimStart(int newLen){
				var copyOff = data.length - newLen;
				var newOff  = offset + copyOff;
				if(newLen<=8){
					return new Word(newOff, WordIO.getWord(data, copyOff, newLen), newLen);
				}
				var bb = new byte[newLen];
				System.arraycopy(data, copyOff, bb, 0, newLen);
				return new Arr(newOff, bb);
			}
			@Override
			boolean tryInlineSet(int localOff, long word, int len){
				var nl = localOff + len;
				if(nl>data.length) data = Arrays.copyOf(data, nl);
				WordIO.setWord(word, data, localOff, len);
				return true;
			}
			@Override
			boolean tryInlineSet(int localOff, byte[] bb, int off, int len){
				var nl = localOff + len;
				if(nl>data.length){
					var old = data;
					data = new byte[nl];
					System.arraycopy(old, 0, data, 0, old.length);
				}
				System.arraycopy(bb, off, data, localOff, len);
				return true;
			}
			@Override
			WriteEvent append(byte[] b, int off, int len){
				var bb = Arrays.copyOf(data, data.length + len);
				System.arraycopy(b, off, bb, data.length, len);
				return new Arr(offset, bb);
			}
			@Override
			WriteEvent append(WriteEvent other){
				var bb = Arrays.copyOf(data, data.length + other.length());
				other.writeTo(bb, data.length, other.length());
				return new Arr(offset, bb);
			}
			@Override
			byte[] byteArr(){ return data; }
			@Override
			WriteEvent prepend(byte[] b, int off, int len){
				var newLen = data.length + len;
				if(newLen<=8){
					return new Word(
						offset - len,
						WordIO.getWord(b, off, len)|
						WordIO.getWord(data, 0, data.length)<<(len*Byte.SIZE),
						(byte)newLen
					);
				}
				var bb = new byte[newLen];
				System.arraycopy(b, off, bb, 0, len);
				System.arraycopy(data, 0, bb, len, data.length);
				return new Arr(offset - len, bb);
			}
			@Override
			public String toString(){ return "A" + start() + " - " + end() + " " + Arrays.toString(data); }
		}
	}
	
	private final class LocalOffIndexIter implements PrimitiveIterator.OfInt{
		private final long    offset;
		private final boolean eventStart;
		
		private int i;
		private int next;
		private int high = writeEvents.size();
		
		public LocalOffIndexIter(long offset, int start, boolean eventStart){
			this.offset = offset;
			this.i = start - 1;
			this.eventStart = eventStart;
			computeNext();
		}
		
		private void computeNext(){
			next = -1;
			while(++i<writeEvents.size()){
				binSearch:
				{
					int low = i;
					if(high - low<2) break binSearch;
					var mid = (low + high)/2;
					
					var eventTmp        = writeEvents.get(mid);
					var eStartTmp       = eventStart? eventTmp.start() : eventTmp.end();
					var localOffsetLTmp = offset - eStartTmp;
					if(localOffsetLTmp>=0){
						i = mid - 1;
						continue;
					}
					high = mid;
				}
				next = i;
				break;
			}
		}
		
		@Override
		public int nextInt(){
			var n = next;
			computeNext();
			return n;
		}
		
		@Override
		public boolean hasNext(){
			return next != -1;
		}
	}
	
	private final class BufferedTransaction implements IOTransaction{
		private final int  startingChunkCount;
		private final long startingTotalBytes;
		
		private final boolean          oldTransactionOpen;
		private final VarHandle        transactionOpenVar;
		private final RandomIO.Creator target;
		
		public BufferedTransaction(boolean oldTransactionOpen, VarHandle transactionOpenVar, RandomIO.Creator target){
			startingChunkCount = DEBUG_VALIDATION && oldTransactionOpen? getChunkCount() : 0;
			startingTotalBytes = DEBUG_VALIDATION && oldTransactionOpen? getTotalBytes() : 0;
			this.oldTransactionOpen = oldTransactionOpen;
			this.transactionOpenVar = transactionOpenVar;
			this.target = target;
		}
		
		@Override
		public int getChunkCount(){
			return IOTransactionBuffer.this.getChunkCount();
		}
		@Override
		public long getTotalBytes(){
			return IOTransactionBuffer.this.getTotalBytes();
		}
		
		private void exportData() throws IOException{
			transactionOpenVar.set(target, oldTransactionOpen);
			if(!oldTransactionOpen){
				var data = export();
				target.io(data::apply);
			}
		}
		
		@Override
		public void close() throws IOException{
			try(var ignore = lock.write()){
				exportData();
			}
		}
		
		@Override
		public String toString(){
			if(oldTransactionOpen){
				if(!DEBUG_VALIDATION){
					return "Transaction{child}";
				}
				return "Transaction{Δ count: " + (startingChunkCount - getChunkCount()) + ", Δ bytes: " + (startingTotalBytes - getTotalBytes()) + "}";
			}
			return "Transaction{count: " + getChunkCount() + ", bytes: " + getTotalBytes() + "}";
		}
	}
	
	private static WriteEvent makeEvent(long offset, byte[] b, int off, int len){
		if(len<=8){
			return makeWordEvent(offset, WordIO.getWord(b, off, len), len);
		}
		byte[] data = new byte[len];
		System.arraycopy(b, off, data, 0, len);
		return new WriteEvent.Arr(offset, data);
	}
	private static WriteEvent makeWordEvent(long offset, long v, int len){
		return new WriteEvent.Word(offset, v, len);
	}
	
	private final List<WriteEvent>      writeEvents = new ArrayList<>();
	private final ReadWriteClosableLock lock;
	
	private long modifiedCapacity = -1;
	
	public IOTransactionBuffer(){
		this(true);
	}
	public IOTransactionBuffer(boolean threadSafe){
		lock = threadSafe? ReadWriteClosableLock.reentrant() : ReadWriteClosableLock.noop();
	}
	
	private static boolean rangeOverlaps(long x1, long x2, long y1, long y2){
		return x1<=(y2 - 1) && y1<=(x2 - 1);
	}
	
	public int readByte(BaseAccess base, long offset) throws IOException{
		int data;
		try(var ignored = lock.read()){
			data = readByte0(offset);
		}
		if(data != -1) return data;
		
		byte[] b    = {0};
		int    read = base.read(offset, b, 0, 1);
		return read != 1? -1 : b[0]&0xFF;
	}
	
	private int readByte0(long offset){
		for(var iter = new LocalOffIndexIter(offset, 0, true); iter.hasNext(); ){
			int i = iter.nextInt();
			
			var event  = writeEvents.get(i);
			var eStart = event.start();
			
			var localOffsetL = offset - eStart;
			if(localOffsetL<0){
				break;
			}
			
			var length = event.length();
			if(localOffsetL>=length) continue;
			var localOffset = (int)localOffsetL;
			
			return event.read1(localOffset);
		}
		return -1;
	}
	
	public int read(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		if(len == 0) return 0;
		try(var ignored = lock.read()){
			return read0(base, offset, b, off, len);
		}
	}
	
	public long readWord(BaseAccess base, long offset, int len) throws IOException{
		if(len == 0) return 0;
		try(var ignored = lock.read()){
			if(writeEvents.isEmpty()){
				byte[] buf = new byte[len];
				base.read(offset, buf, 0, len);
				return WordIO.getWord(buf, 0, len);
			}
			
			var newEnd = offset + len;
			for(var iter = new LocalOffIndexIter(offset, 0, true); iter.hasNext(); ){
				var i = iter.nextInt();
				
				var event = writeEvents.get(i);
				
				var eStart = event.start();
				if(offset<eStart){
					if(newEnd<eStart){
						break;
					}
					continue;
				}
				
				var end = event.end();
				if(end>=newEnd){
					var eventOffset = (int)(offset - eStart);
					return event.readWord(eventOffset, len);
				}
				break;
			}
			
			byte[] buf = readFully(base, offset, len);
			return WordIO.getWord(buf, 0, len);
		}
	}
	
	private byte[] readFully(BaseAccess base, long offset, int len) throws IOException{
		byte[] buf       = new byte[len];
		var    rem       = len;
		var    totalRead = 0;
		while(rem>0){
			var read = read0(base, offset + totalRead, buf, totalRead, len);
			if(read<=-1){
				throw new EOFException();
			}
			rem -= read;
			totalRead += read;
		}
		return buf;
	}
	
	private int read0(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		WriteEvent next;
		
		long cursor = offset;
		
		int rem = len, localOff = off, eventsStart = 0;
		
		wh:
		while(rem>0){
			next = null;
			
			for(var iter = new LocalOffIndexIter(cursor, eventsStart, true); iter.hasNext(); ){
				int i = iter.nextInt();
				
				var event  = writeEvents.get(i);
				var eStart = event.start();
				
				var localOffsetL = cursor - eStart;
				if(localOffsetL<0){
					next = event;
					break;
				}
				
				var length = event.length();
				if(localOffsetL>=length) continue;
				var localOffset = (int)localOffsetL;
				
				var remaining = length - localOffset;
				var toCopy    = Math.min(remaining, rem);
				
				event.writeTo(localOffset, b, localOff, toCopy);
				
				cursor += toCopy;
				localOff += toCopy;
				rem -= toCopy;
				
				eventsStart++;
				
				continue wh;
			}
			
			int tillNext;
			if(next == null) tillNext = rem;
			else{
				tillNext = (int)(next.offset - cursor);
			}
			var toRead = Math.min(tillNext, rem);
			var read   = base.read(cursor, b, localOff, toRead);
			if(read>0){
				cursor += read;
				localOff += read;
				rem -= read;
			}
			if(read != toRead){
				if(len == rem) return -1;
				return len - rem;
			}
			if(next != null && rem>0){
				var length = next.length();
				var toCopy = Math.min(length, rem);
				next.writeTo(b, localOff, toCopy);
				cursor += toCopy;
				localOff += toCopy;
				rem -= toCopy;
			}
		}
		if(rem != 0) throw new AssertionError();
		return len;
	}
	
	public long getCapacity(long fallback){
		try(var ignored = lock.read()){
			return getCapacity0(fallback);
		}
	}
	private long getCapacity0(long fallback){
		if(modifiedCapacity == -1){
			if(!writeEvents.isEmpty()){
				var last = writeEvents.getLast();
				if(last.end()>fallback){
					modifiedCapacity = last.end();
					return last.end();
				}
			}
			return fallback;
		}
		return modifiedCapacity;
	}
	
	public void capacityChange(long newCapacity){
		try(var ignored = lock.write()){
			capacityChange0(newCapacity);
		}
	}
	private void capacityChange0(long newCapacity){
		if(newCapacity<0) throw new IllegalArgumentException();
		modifiedCapacity = newCapacity;
		
		for(int i = writeEvents.size() - 1; i>=0; i--){
			var e = writeEvents.get(i);
			if(e.start()>=newCapacity){
				writeEvents.remove(i);
				continue;
			}
			if(e.end()>newCapacity){
				int shrink = (int)(e.end() - newCapacity);
				assert shrink<e.length() : newCapacity;
				assert shrink>0;
				writeEvents.set(i, e.trim(e.length() - shrink));
				continue;
			}
			break;
		}
		
	}
	
	public void writeByte(long offset, int b) throws IOException{
		write(offset, new byte[]{(byte)b}, 0, 1);
	}
	public void writeChunks(Collection<RandomIO.WriteChunk> writeData){
		try(var ignored = lock.write()){
			for(var e : writeData){
				write0(e.ioOffset(), e.data(), e.dataOffset(), e.dataLength());
			}
		}
	}
	public void write(long offset, byte[] b, int off, int len) throws IOException{
		try(var ignored = lock.write()){
			write0(offset, b, off, len);
		}
	}
	public void writeWord(long offset, long v, int len) throws IOException{
		try(var ignored = lock.write()){
			writeWord0(offset, v, len);
		}
	}
	
	private void writeWord0(long offset, long v, int len){
		var newEnd = offset + len;
		if(writeEvents.isEmpty()){
			writeEvents.add(makeWordEvent(offset, v, len));
			return;
		}
		var offEnd = offset + len;
		for(var iter = new LocalOffIndexIter(offset, 0, true); iter.hasNext(); ){
			var i     = iter.nextInt();
			var event = writeEvents.get(i);
			var s     = event.start();
			if(offEnd<s) continue;
			if(offset<s) continue;
			
			var end = event.end();
			if(end>=newEnd){
				var start       = event.start();
				var eventOffset = (int)(offset - start);
				if(event.tryInlineSet(eventOffset, v, len)){
					return;
				}
			}
			break;
		}
		
		byte[] arr = new byte[len];
		WordIO.setWord(v, arr, 0, len);
		write0(offset, arr, 0, len);
	}
	
	private void write0(long offset, byte[] b, int off, int len){
		if(len == 0) return;
		var newStart = offset;
		var newEnd   = offset + len;
		try{
			if(writeEvents.isEmpty()){
				writeEvents.add(makeEvent(offset, b, off, len));
				return;
			}
			
			for(var iter = new LocalOffIndexIter(newStart, 0, false); iter.hasNext(); ){
				final int i = iter.nextInt();
				
				var event = writeEvents.get(i);
				
				var eStart = event.start();
				var eEnd   = event.end();
				
				if(eEnd == newStart){
					if(checkNext(offset, b, off, len, i, event.end() + len)) return;
					
					writeEvents.set(i, event.append(b, off, len));
					return;
				}
				if(newEnd == eStart){
					var e = event.prepend(b, off, len);
					setEventSorted(i, e);
					return;
				}
				if(rangeOverlaps(newStart, newEnd, eStart, eEnd)){
					
					var joinedEnd = Math.max(newEnd, eEnd);
					
					if(checkNext(offset, b, off, len, i, joinedEnd)) return;
					
					//new event completely contains existing event, existing event can be replaced with new one
					if(newStart<=eStart && newEnd>=eEnd){
						setEventSorted(i, makeEvent(offset, b, off, len));
						return;
					}
					
					if(newStart>=eStart){
						var eOff    = Math.toIntExact(newStart - eStart);
						var success = event.tryInlineSet(eOff, b, off, len);
						if(success) return;
					}
					
					mergeWriteEvents(b, off, len, newStart, eStart, joinedEnd, event, i);
					return;
				}
				if(eEnd>newStart){
					break;
				}
			}
			UtilL.addRemainSorted(writeEvents, makeEvent(offset, b, off, len));
		}finally{
			if(modifiedCapacity != -1){
				var last = writeEvents.getLast();
				if(last.end()>modifiedCapacity){
					modifiedCapacity = last.end();
				}
			}

//			if(DEBUG_VALIDATION) validate();
		}
	}
	private void mergeWriteEvents(byte[] b, int off, int len, long newStart, long eStart, long joinedEnd, WriteEvent event, int i){
		var joinedStart = Math.min(newStart, eStart);
		var joinedSize  = Math.toIntExact(joinedEnd - joinedStart);
		
		var newOff = (int)(newStart - joinedStart);
		var eOff   = (int)(eStart - joinedStart);
		
		WriteEvent modEvent;
		if(joinedSize<=8){
			var newOffB = newOff*Byte.SIZE;
			var mask    = BitUtils.makeMask(len*Byte.SIZE);
			
			var ew = event.asWord();
			var w  = WordIO.getWord(b, off, len);
			
			modEvent = new WriteEvent.Word(
				joinedStart,
				(ew<<(eOff*Byte.SIZE))&(~(mask<<newOffB))|
				w<<newOffB,
				joinedSize);
		}else{
			byte[] data = new byte[joinedSize];
			event.writeTo(data, eOff, event.length());
			System.arraycopy(b, off, data, newOff, len);
			modEvent = new WriteEvent.Arr(joinedStart, data);
		}
		setEventSorted(i, modEvent);
	}
	
	private void setEventSorted(int i, WriteEvent event){
		var old = writeEvents.set(i, event);
		if(old.offset == event.offset) return;
		correctEventOffset(i, event);
	}
	private void correctEventOffset(int eventIndex, WriteEvent event){
		boolean swapped = false;
		while(eventIndex>0){
			var prevI = eventIndex - 1;
			var prev  = writeEvents.get(prevI);
			if(prev.compareTo(event)>0){
				Collections.swap(writeEvents, eventIndex, prevI);
				eventIndex--;
				swapped = true;
			}else{
				if(!swapped) break;
				return;
			}
		}
		
		while(true){
			var nextI = eventIndex + 1;
			if(nextI>=writeEvents.size()) return;
			var next = writeEvents.get(nextI);
			if(event.compareTo(next)>0){
				Collections.swap(writeEvents, eventIndex, nextI);
				eventIndex++;
				swapped = true;
			}else{
				if(!swapped) break;
				return;
			}
		}
	}
	
	private void validate(){
		var copy = new ArrayList<>(writeEvents);
		copy.sort(WriteEvent::compareTo);
		if(!copy.equals(writeEvents)) throw new AssertionError("\n" + copy + "\n" + writeEvents);
		
		for(var event1 : writeEvents){
			for(var event2 : writeEvents){
				if(event1 == event2) continue;
				if(event1.end() == event2.start() ||
				   event2.end() == event1.start() ||
				   rangeOverlaps(event1.start(), event1.end(), event2.start(), event2.end())){
					throw new RuntimeException(event1 + " " + event2 + " " + writeEvents);
				}
			}
		}
	}
	
	private boolean checkNext(long offset, byte[] b, int off, int len, int i, long writeEnd){
		while(i<writeEvents.size() - 1){
			var iNext = i + 1;
			var next  = writeEvents.get(iNext);
			//multi merge
			var nextOverwrite = (int)(writeEnd - next.start());
			if(nextOverwrite>=0){
				//If true, then the next event is completely overwritten. Can be removed
				if(nextOverwrite>=next.length()){
					writeEvents.remove(iNext);
					continue;
				}
				WriteEvent e = next;
				if(nextOverwrite>0){
					e = next.trimStart(next.length() - nextOverwrite);
				}
				e = e.prepend(b, off, len);
				assert e.offset == offset : e.offset + " != " + offset;
				
				//Make sure expanding next element backwards does not overlap current (i) element. Handle collisions
				while(i<writeEvents.size() - 1){
					var current         = writeEvents.get(i);
					var currentOverride = (int)(current.end() - offset);
					if(currentOverride<0) break;
					if(current.offset>=offset){
						writeEvents.remove(i);
						iNext--;
						if(i == 0) break;
						i--;
						continue;
					}
					if(currentOverride == 0){
						writeEvents.set(i, current.append(e));
						writeEvents.remove(iNext);
						return true;
					}
					var combined = trimAndAppend(current, currentOverride, e);
					writeEvents.set(i, combined);
					writeEvents.remove(iNext);
					return true;
				}
				
				writeEvents.set(iNext, e);
				return true;
			}
			break;
		}
		return false;
	}
	
	private static WriteEvent trimAndAppend(WriteEvent first, int firstShrink, WriteEvent append){
		var        trimLen = first.length() - firstShrink;
		var        newLen  = trimLen + append.length();
		WriteEvent combined;
		if(newLen<=8){
			var w  = first.readWord(0, trimLen);
			var w2 = append.asWord();
			combined = new WriteEvent.Word(first.offset, w|(w2<<(trimLen*Byte.SIZE)), newLen);
		}else{
			var data = new byte[newLen];
			first.writeTo(data, 0, trimLen);
			append.writeTo(data, trimLen, append.length());
			combined = new WriteEvent.Arr(first.offset, data);
		}
		return combined;
	}
	
	public IOTransaction open(RandomIO.Creator target, VarHandle transactionOpenVar){
		var oldTransactionOpen = (boolean)transactionOpenVar.get(target);
		transactionOpenVar.set(target, true);
		return new BufferedTransaction(oldTransactionOpen, transactionOpenVar, target);
	}
	public TransactionExport export(){
		try(var ignored = lock.write()){
			return export0();
		}
	}
	private TransactionExport export0(){
		var writes = new ArrayList<RandomIO.WriteChunk>(writeEvents.size());
		for(var e : writeEvents){
			writes.add(new RandomIO.WriteChunk(e.offset, e.byteArr()));
		}
		
		var setCapacity = OptionalPP.<Long>empty();
		if(modifiedCapacity != -1){
			setCapacity = OptionalPP.of(modifiedCapacity);
		}
		reset();
		
		return new TransactionExport(setCapacity, writes);
	}
	
	private void reset(){
		writeEvents.clear();
		modifiedCapacity = -1;
	}
	
	public String infoString(){
		try(var ignored = lock.read()){
			if(writeEvents.isEmpty()) return "no data";
			return getTotalBytes() + " bytes overridden in range " + writeEvents.getFirst().start() + " - " + writeEvents.getLast().end();
		}
	}
	
	public int getChunkCount(){
		try(var ignored = lock.read()){
			return writeEvents.size();
		}
	}
	
	public long getTotalBytes(){
		try(var ignored = lock.read()){
			int sum = 0;
			for(WriteEvent writeEvent : writeEvents){
				sum += writeEvent.length();
			}
			return sum;
		}
	}
	
}
