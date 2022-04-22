package com.lapissea.cfs.io;

import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class IOTransactionBuffer{
	
	private interface IOEvent{
		record Write(long offset, byte[] data) implements IOEvent, Comparable<Write>{
			long start(){
				return offset;
			}
			long end(){
				return offset+data.length;
			}
			@Override
			public String toString(){
				return start()+" - "+end();
			}
			@Override
			public int compareTo(Write o){
				return Long.compare(offset, o.offset);
			}
		}
	}
	
	private final List<IOEvent.Write> writeEvents=new ArrayList<>();
	
	private boolean rangeOverlaps(long x1, long x2, long y1, long y2){
		return x1<=(y2-1)&&y1<=(x2-1);
	}
	
	public interface BaseAccess{
		int read(long offset, byte[] b, int off, int len) throws IOException;
	}
	
	public int readByte(BaseAccess base, long offset) throws IOException{
		
		int data=readSingle(offset);
		if(data!=-1) return data;
		
		byte[] b   ={0};
		int    read=base.read(offset, b, 0, 1);
		return read!=1?-1:b[0]&0xFF;
	}
	
	private int readSingle(long offset){
		for(IOEvent.Write event : writeEvents){
			var eStart=event.start();
			
			var localOffsetL=offset-eStart;
			if(localOffsetL<0){
				continue;
			}
			
			var length=event.data.length;
			if(localOffsetL>=length) continue;
			var localOffset=(int)localOffsetL;
			
			return event.data[localOffset]&0xFF;
		}
		return -1;
	}
	
	public int read(BaseAccess base, long offset, byte[] b, int off, int len) throws IOException{
		if(len==0) return 0;
		if(len==1){
			var byt=readSingle(offset);
			if(byt!=-1){
				b[off]=(byte)byt;
				return 1;
			}
		}
		
		IOEvent.Write next;
		
		long cursor=offset;
		
		int rem=len, localOff=off, eventsStart=0;
		
		wh:
		while(rem>0){
			next=null;
			
			for(int i=eventsStart;i<writeEvents.size();i++){
				var event =writeEvents.get(i);
				var eStart=event.start();
				
				var localOffsetL=cursor-eStart;
				if(localOffsetL<0){
					if(next==null) next=event;
					else{
						if(next.offset>event.offset){
							next=event;
						}
					}
					continue;
				}
				
				var length=event.data.length;
				if(localOffsetL>=length) continue;
				var localOffset=(int)localOffsetL;
				
				var remaining=length-localOffset;
				var toCopy   =Math.min(remaining, rem);
				
				System.arraycopy(event.data, localOffset, b, localOff, toCopy);
				
				cursor+=toCopy;
				localOff+=toCopy;
				rem-=toCopy;
				
				eventsStart++;
				
				continue wh;
			}
			
			int tillNext;
			if(next==null) tillNext=rem;
			else{
				tillNext=(int)(next.offset-cursor);
			}
			var toRead=Math.min(tillNext, rem);
			var read  =base.read(cursor, b, localOff, toRead);
			cursor+=read;
			localOff+=read;
			rem-=read;
			if(read!=toRead){
				return len-rem;
			}
		}
		assert rem==0;
		return len;
	}
	
	private long modifiedCapacity=-1;
	
	public long getCapacity(long fallback){
		if(modifiedCapacity==-1){
			if(!writeEvents.isEmpty()){
				var last=writeEvents.get(writeEvents.size()-1);
				if(last.end()>fallback){
					modifiedCapacity=last.end();
					return last.end();
				}
			}
			return fallback;
		}
		return modifiedCapacity;
	}
	
	public void capacityChange(long newCapacity){
		if(newCapacity<0) throw new IllegalArgumentException();
		modifiedCapacity=newCapacity;
		
		for(int i=writeEvents.size()-1;i>=0;i--){
			var e=writeEvents.get(i);
			if(e.start()>newCapacity){
				writeEvents.remove(i);
				continue;
			}
			if(e.end()>newCapacity){
				int shrink=(int)(e.end()-newCapacity);
				assert shrink<e.data.length;
				assert shrink>0;
				writeEvents.set(i, makeEvent(e.offset, e.data, 0, e.data.length-shrink));
				continue;
			}
			break;
		}
		
	}
	
	public void writeByte(long offset, int b){
		write(offset, new byte[]{(byte)b}, 0, 1);
	}
	
	public void write(long offset, byte[] b, int off, int len){
		if(len==0) return;
		var newStart=offset;
		var newEnd  =offset+len;
		try{
			if(!writeEvents.isEmpty()){
				if(writeEvents.get(0).start()>newEnd){
					writeEvents.add(0, makeEvent(offset, b, off, len));
					return;
				}
				if(writeEvents.get(writeEvents.size()-1).end()<newStart){
					writeEvents.add(makeEvent(offset, b, off, len));
					return;
				}
			}else{
				writeEvents.add(makeEvent(offset, b, off, len));
				return;
			}
			
			for(int i=0;i<writeEvents.size();i++){
				var event=writeEvents.get(i);
				
				var eStart=event.start();
				var eEnd  =event.end();
				
				if(eEnd==newStart){
					if(checkNext(offset, b, off, len, i, event.end()+len)) return;
					
					byte[] data=Arrays.copyOf(event.data, event.data.length+len);
					System.arraycopy(b, off, data, event.data.length, len);
					writeEvents.set(i, new IOEvent.Write(event.offset, data));
					return;
				}
				if(newEnd==eStart){
//					if(checkNext(offset, b, off, len, i, event.end()+len)) return;
					byte[] data=new byte[event.data.length+len];
					System.arraycopy(b, off, data, 0, len);
					System.arraycopy(event.data, 0, data, len, event.data.length);
					setEventSorted(i, new IOEvent.Write(offset, data));
					return;
				}
				if(rangeOverlaps(newStart, newEnd, eStart, eEnd)){
					
					var start=Math.min(newStart, eStart);
					var end  =Math.max(newEnd, eEnd);
					
					if(checkNext(offset, b, off, len, i, end)) return;
					
					//new event completely contains existing event, existing event can be replaced wi
					if(newStart<eStart&&newEnd>eEnd){
						byte[] data=new byte[len];
						System.arraycopy(b, off, data, 0, len);
						setEventSorted(i, new IOEvent.Write(offset, data));
						return;
					}
					
					var size=Math.toIntExact(end-start);
					
					var newOff=Math.toIntExact(newStart-start);
					var eOff  =Math.toIntExact(eStart-start);
					
					//existing event completely contains new event, existing event can be modified
					if(newStart>eStart&&newEnd<eEnd){
						System.arraycopy(b, off, event.data, newOff, len);
						return;
					}
					
					byte[] data=new byte[size];
					System.arraycopy(event.data, 0, data, eOff, event.data.length);
					System.arraycopy(b, off, data, newOff, len);
					setEventSorted(i, new IOEvent.Write(start, data));
					return;
				}
			}
			UtilL.addRemainSorted(writeEvents, makeEvent(offset, b, off, len));
		}finally{
			if(modifiedCapacity!=-1){
				var last=writeEvents.get(writeEvents.size()-1);
				if(last.end()>modifiedCapacity){
					modifiedCapacity=last.end();
				}
			}
			if(DEBUG_VALIDATION){
				var copy=new ArrayList<>(writeEvents);
				copy.sort(IOEvent.Write::compareTo);
				assert copy.equals(writeEvents):"\n"+copy+"\n"+writeEvents;
				
				for(var event1 : writeEvents){
					for(var event2 : writeEvents){
						if(event1==event2) continue;
						if(event1.end()==event2.start()||
						   event2.end()==event1.start()||
						   rangeOverlaps(event1.start(), event1.end(), event2.start(), event2.end())){
							throw new RuntimeException(event1+" "+event2+" "+writeEvents);
						}
					}
				}
			}
		}
	}
	private void setEventSorted(int i, IOEvent.Write m){
		var old=writeEvents.set(i, m);
		if(old.offset==m.offset) return;
		if(i>0){
			var prev=writeEvents.get(i-1);
			if(prev.compareTo(m)>0){
				writeEvents.sort(IOEvent.Write::compareTo);
				return;
			}
		}
		if(i+1<writeEvents.size()){
			var next=writeEvents.get(i+1);
			if(m.compareTo(next)>0){
				writeEvents.sort(IOEvent.Write::compareTo);
				return;
			}
		}
	}
	private boolean checkNext(long offset, byte[] b, int off, int len, int i, long end){
		while(i<writeEvents.size()-1){
			var next=writeEvents.get(i+1);
			//multi merge
			var nextOverwrite=(int)(end-next.start());
			if(nextOverwrite>=0){
				writeEvents.remove(i+1);
				if(nextOverwrite>=next.data.length){
					continue;
				}
				write(offset, b, off, len);
				
				if(nextOverwrite==0){
					write(next.offset, next.data, 0, next.data.length);
					return true;
				}
				
				byte[] trimmed=new byte[next.data.length-nextOverwrite];
				System.arraycopy(next.data, nextOverwrite, trimmed, 0, trimmed.length);
				write(next.offset+nextOverwrite, trimmed, 0, trimmed.length);
				return true;
			}
			break;
		}
		return false;
	}
	
	private IOEvent.Write makeEvent(long offset, byte[] b, int off, int len){
		byte[] data=new byte[len];
		System.arraycopy(b, off, data, 0, len);
		return new IOEvent.Write(offset, data);
	}
	
	public record TransactionExport(OptionalLong setCapacity, List<RandomIO.WriteChunk> writes){}
	
	public TransactionExport export(){
		var writes     =writeEvents.stream().map(e->new RandomIO.WriteChunk(e.offset, e.data)).toList();
		var setCapacity=OptionalLong.empty();
		if(modifiedCapacity!=-1){
			setCapacity=OptionalLong.of(modifiedCapacity);
		}
		reset();
		
		return new TransactionExport(setCapacity, writes);
	}
	
	private void reset(){
		writeEvents.clear();
		modifiedCapacity=-1;
	}
	
	public String infoString(){
		if(writeEvents.isEmpty()) return "no data";
		return writeEvents.stream().mapToInt(e->e.data.length).sum()+" bytes overridden in range "+writeEvents.get(0).start()+" - "+writeEvents.get(writeEvents.size()-1).end();
	}
}
