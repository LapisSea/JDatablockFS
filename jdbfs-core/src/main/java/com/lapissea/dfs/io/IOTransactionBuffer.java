package com.lapissea.dfs.io;

import com.lapissea.dfs.io.content.WordIO;
import com.lapissea.dfs.utils.IntHashSet;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.util.UtilL;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
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
	
	private record WriteEvent(long offset, byte[] data) implements Comparable<WriteEvent>{
		long start(){
			return offset;
		}
		long end(){
			return offset + data.length;
		}
		@Override
		public String toString(){
			return start() + " - " + end();
		}
		@Override
		public int compareTo(WriteEvent o){
			return Long.compare(offset, o.offset);
		}
	}
	
	private final List<WriteEvent>      writeEvents = new ArrayList<>();
	private final ReadWriteClosableLock lock;
	
	public IOTransactionBuffer(){
		this(true);
	}
	public IOTransactionBuffer(boolean threadSafe){
		lock = threadSafe? ReadWriteClosableLock.reentrant() : ReadWriteClosableLock.noop();
	}
	
	private static boolean rangeOverlaps(long x1, long x2, long y1, long y2){
		return x1<=(y2 - 1) && y1<=(x2 - 1);
	}
	
	public interface BaseAccess{
		int read(long offset, byte[] b, int off, int len) throws IOException;
	}
	
	public int readByte(BaseAccess base, long offset) throws IOException{
		
		int data;
		
		try(var ignored = lock.read()){
			data = readSingle(offset);
		}
		if(data != -1) return data;
		
		byte[] b    = {0};
		int    read = base.read(offset, b, 0, 1);
		return read != 1? -1 : b[0]&0xFF;
	}
	
	private class LocalOffIndexIter implements PrimitiveIterator.OfInt{
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
	
	private int readSingle(long offset){
		for(var iter = new LocalOffIndexIter(offset, 0, true); iter.hasNext(); ){
			int i = iter.nextInt();
			
			var event  = writeEvents.get(i);
			var eStart = event.start();
			
			var localOffsetL = offset - eStart;
			if(localOffsetL<0){
				continue;
			}
			
			var length = event.data.length;
			if(localOffsetL>=length) continue;
			var localOffset = (int)localOffsetL;
			
			return event.data[localOffset]&0xFF;
		}
		return -1;
	}
	
	public int read(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		if(len == 0) return 0;
		try(var ignored = lock.read()){
			if(len == 1){
				int byt = readSingle(offset);
				if(byt != -1){
					b[off] = (byte)byt;
					return 1;
				}
			}
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
				if(offset<eStart) continue;
				
				var end = event.end();
				if(end>=newEnd){
					var eventOffset = (int)(offset - eStart);
					return WordIO.getWord(event.data, eventOffset, len);
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
					if(next == null) next = event;
					else{
						if(next.offset>event.offset){
							next = event;
						}
					}
					continue;
				}
				
				var length = event.data.length;
				if(localOffsetL>=length) continue;
				var localOffset = (int)localOffsetL;
				
				var remaining = length - localOffset;
				var toCopy    = Math.min(remaining, rem);
				
				System.arraycopy(event.data, localOffset, b, localOff, toCopy);
				
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
		}
		if(rem != 0) throw new AssertionError();
		return len;
	}
	
	private long modifiedCapacity = -1;
	
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
				assert shrink<e.data.length : newCapacity;
				assert shrink>0;
				writeEvents.set(i, makeEvent(e.offset, e.data, 0, e.data.length - shrink));
				continue;
			}
			break;
		}
		
	}
	
	
	private static WriteEvent makeEvent(long offset, byte[] b, int off, int len){
		byte[] data = new byte[len];
		System.arraycopy(b, off, data, 0, len);
		return new WriteEvent(offset, data);
	}
	private static WriteEvent makeWordEvent(long offset, long v, int len){
		byte[] arr = new byte[len];
		WordIO.setWord(v, arr, 0, len);
		return new WriteEvent(offset, arr);
	}
	
	public void writeByte(BaseAccess base, long offset, int b) throws IOException{
		write(base, offset, new byte[]{(byte)b}, 0, 1);
	}
	
	public void writeChunks(BaseAccess base, Collection<RandomIO.WriteChunk> writeData) throws IOException{
		try(var ignored = lock.write()){
			for(var e : writeData){
				write0(base, e.ioOffset(), e.data(), e.dataOffset(), e.dataLength());
			}
		}
	}
	
	public void write(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		try(var ignored = lock.write()){
			write0(base, offset, b, off, len);
		}
	}
	
	public void writeWord(BaseAccess base, long offset, long v, int len) throws IOException{
		try(var ignored = lock.write()){
			var newEnd = offset + len;
			if(!writeEvents.isEmpty()){
				if(writeEvents.getFirst().start()>newEnd){
					writeEvents.addFirst(makeWordEvent(offset, v, len));
					markIndexDirty(base, 0, offset);
					return;
				}
				if(writeEvents.getLast().end()<offset){
					writeEvents.add(makeWordEvent(offset, v, len));
					markIndexDirty(base, writeEvents.size() - 1, offset);
					return;
				}
			}else{
				writeEvents.add(makeWordEvent(offset, v, len));
				return;
			}
			
			for(var iter = new LocalOffIndexIter(offset, 0, true); iter.hasNext(); ){
				var i = iter.nextInt();
				
				var event = writeEvents.get(i);
				if(offset<event.start()) continue;
				
				var end = event.end();
				if(end>=newEnd){
					var start       = event.start();
					var eventOffset = (int)(offset - start);
					WordIO.setWord(v, event.data, eventOffset, len);
					return;
				}
				break;
			}
			
			byte[] arr = new byte[len];
			WordIO.setWord(v, arr, 0, len);
			write0(base, offset, arr, 0, len);
		}
	}
	
	private void write0(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		if(len == 0) return;
		var newStart = offset;
		var newEnd   = offset + len;
		try{
			if(!writeEvents.isEmpty()){
				if(writeEvents.getFirst().start()>newEnd){
					writeEvents.addFirst(makeEvent(offset, b, off, len));
					markIndexDirty(base, 0, offset);
					return;
				}
				if(writeEvents.getLast().end()<newStart){
					writeEvents.add(makeEvent(offset, b, off, len));
					markIndexDirty(base, writeEvents.size() - 1, offset);
					return;
				}
			}else{
				writeEvents.add(makeEvent(offset, b, off, len));
				return;
			}
			
			for(var iter = new LocalOffIndexIter(newStart, 0, false); iter.hasNext(); ){
				int i = iter.nextInt();
				
				var event = writeEvents.get(i);
				
				var eStart = event.start();
				var eEnd   = event.end();
				
				if(eEnd == newStart){
					if(checkNext(base, offset, b, off, len, i, event.end() + len)) return;
					
					byte[] data = Arrays.copyOf(event.data, event.data.length + len);
					System.arraycopy(b, off, data, event.data.length, len);
					writeEvents.set(i, new WriteEvent(event.offset, data));
					markIndexDirty(base, i, offset);
					return;
				}
				if(newEnd == eStart){
//					if(checkNext(offset, b, off, len, i, event.end()+len)) return;
					byte[] data = new byte[event.data.length + len];
					System.arraycopy(b, off, data, 0, len);
					System.arraycopy(event.data, 0, data, len, event.data.length);
					setEventSorted(base, i, new WriteEvent(offset, data), offset);
					return;
				}
				if(rangeOverlaps(newStart, newEnd, eStart, eEnd)){
					
					var start = Math.min(newStart, eStart);
					var end   = Math.max(newEnd, eEnd);
					
					if(checkNext(base, offset, b, off, len, i, end)) return;
					
					//new event completely contains existing event, existing event can be replaced wi
					if(newStart<eStart && newEnd>eEnd){
						byte[] data = new byte[len];
						System.arraycopy(b, off, data, 0, len);
						setEventSorted(base, i, new WriteEvent(offset, data), offset);
						return;
					}
					
					var size = Math.toIntExact(end - start);
					
					var newOff = Math.toIntExact(newStart - start);
					var eOff   = Math.toIntExact(eStart - start);
					
					//existing event completely contains new event, existing event can be modified
					if(newStart>eStart && newEnd<eEnd){
						System.arraycopy(b, off, event.data, newOff, len);
						return;
					}
					
					byte[] data = new byte[size];
					System.arraycopy(event.data, 0, data, eOff, event.data.length);
					System.arraycopy(b, off, data, newOff, len);
					setEventSorted(base, i, new WriteEvent(start, data), offset);
					return;
				}
			}
			UtilL.addRemainSorted(writeEvents, makeEvent(offset, b, off, len));
			markIndexDirty(base, (int)(offset%writeEvents.size()), offset);
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
	
	private final IntHashSet dirty = new IntHashSet();
	private       boolean    merging;
	
	private void markIndexDirty(BaseAccess base, int i, long jitter) throws IOException{
		if(writeEvents.size()<64 || merging) return;
		if(i>0) dirty.add(i - 1);
		dirty.add(i);
		merging = true;
		doMerge(base, jitter);
		merging = false;
	}
	
	private void doMerge(BaseAccess base, long jitter) throws IOException{
		var bufs = new ArrayList<WriteEvent>(dirty.size());
		
		var addFac = 40;
		int count  = writeEvents.size()/addFac;
		for(int i = 0; i<count; i++){
			var id = (int)(Math.abs(jitter + i*addFac)%(writeEvents.size() - 1));
			dirty.remove(id);
			acumMergeBuf(base, id, bufs);
		}
		for(var ic : dirty){
			var i = ic.value;
			acumMergeBuf(base, i, bufs);
		}
		dirty.clear();
		
		for(var buf : bufs){
			write0(base, buf.offset, buf.data, 0, buf.data.length);
		}
	}
	private void acumMergeBuf(BaseAccess base, int i, ArrayList<WriteEvent> bufs) throws IOException{
		if(i + 1>=writeEvents.size()) return;
		var e1End    = writeEvents.get(i).end();
		var e2Start  = writeEvents.get(i + 1).start();
		var dist     = (int)(e2Start - e1End);
		var jumpSize = writeEvents.size()/4;
		if(dist<=jumpSize){
			var bb   = new byte[dist];
			var read = read0(base, e1End, bb, 0, bb.length);
			bufs.add(new WriteEvent(e1End, bb.length != read? Arrays.copyOf(bb, read) : bb));
		}
	}
	
	private void setEventSorted(BaseAccess base, int i, WriteEvent m, long jitter) throws IOException{
		var old = writeEvents.set(i, m);
		if(old.offset == m.offset) return;
		if(i>0){
			var prev = writeEvents.get(i - 1);
			if(prev.compareTo(m)>0){
				writeEvents.sort(WriteEvent::compareTo);
				markIndexDirty(base, i - 1, jitter);
				return;
			}
		}
		if(i + 1<writeEvents.size()){
			var next = writeEvents.get(i + 1);
			if(m.compareTo(next)>0){
				writeEvents.sort(WriteEvent::compareTo);
				markIndexDirty(base, i + 1, jitter);
				return;
			}
		}
	}
	private boolean checkNext(BaseAccess base, long offset, byte[] b, int off, int len, int i, long end) throws IOException{
		while(i<writeEvents.size() - 1){
			var next = writeEvents.get(i + 1);
			//multi merge
			var nextOverwrite = (int)(end - next.start());
			if(nextOverwrite>=0){
				writeEvents.remove(i + 1);
				if(nextOverwrite>=next.data.length){
					continue;
				}
				write0(base, offset, b, off, len);
				
				if(nextOverwrite == 0){
					write0(base, next.offset, next.data, 0, next.data.length);
					return true;
				}
				
				byte[] trimmed = new byte[next.data.length - nextOverwrite];
				System.arraycopy(next.data, nextOverwrite, trimmed, 0, trimmed.length);
				write0(base, next.offset + nextOverwrite, trimmed, 0, trimmed.length);
				return true;
			}
			break;
		}
		return false;
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
	
	public TransactionExport export(){
		try(var ignored = lock.write()){
			return export0();
		}
	}
	private TransactionExport export0(){
		var writes = new ArrayList<RandomIO.WriteChunk>(writeEvents.size());
		for(var e : writeEvents){
			writes.add(new RandomIO.WriteChunk(e.offset, e.data));
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
				sum += writeEvent.data.length;
			}
			return sum;
		}
	}
	
	public IOTransaction open(RandomIO.Creator target, VarHandle transactionOpenVar){
		var oldTransactionOpen = (boolean)transactionOpenVar.get(target);
		transactionOpenVar.set(target, true);
		
		return new IOTransaction(){
			private final int  startingChunkCount = DEBUG_VALIDATION && oldTransactionOpen? getChunkCount() : 0;
			private final long startingTotalBytes = DEBUG_VALIDATION && oldTransactionOpen? getTotalBytes() : 0;
			
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
			
			private void exportDataDeb() throws IOException{
				if(oldTransactionOpen){
					transactionOpenVar.set(target, true);
					return;
				}
				var expected = target.readAll();
				var data     = export();
				transactionOpenVar.set(target, false);
				target.io(data::apply);
				var actual = target.readAll();
				if(!Arrays.equals(expected, actual)){
					var baos = new ByteArrayOutputStream();
					try(var oos = new ObjectOutputStream(baos)){ oos.writeObject(data); }
					throw new AssertionError("Transaction before and after apply differs!\n" +
					                         "Expected: " + Arrays.toString(expected) + "\n" +
					                         "Actual:   " + Arrays.toString(actual) + "\n" +
					                         "Transaction:\n" + Base64.getEncoder().encodeToString(baos.toByteArray()));
				}
			}
			
			@Override
			public void close() throws IOException{
				try(var ignore = lock.write()){
					if(DEBUG_VALIDATION) exportDataDeb();
					else exportData();
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
		};
	}
}
