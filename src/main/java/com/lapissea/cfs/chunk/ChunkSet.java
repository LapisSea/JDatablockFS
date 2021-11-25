package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.NotNull;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.util.Iterator;
import java.util.OptionalLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public class ChunkSet implements Iterable<ChunkPointer>{
	
	private final Roaring64Bitmap index=new Roaring64Bitmap();
	private       long            end  =-1;
	private       long            start=-1;
	private       long            size;
	
	public ChunkSet(){
	}
	
	public ChunkSet(Stream<ChunkPointer> data){
		data.forEach(this::add);
	}
	public ChunkSet(Iterable<ChunkPointer> data){
		data.forEach(this::add);
	}
	
	
	public ChunkPointer first(){
		return ChunkPointer.of(start);
	}
	public ChunkPointer last(){
		return ChunkPointer.of(lastIndex());
	}
	
	public long lastIndex(){
		return end-1;
	}
	public boolean isEmpty(){
		return start==end;
	}
	public long rageSize(){
		return end-start;
	}
	public long size(){
		return size;
	}
	
	public void add(Chunk chunk)     {add(chunk.getPtr());}
	public void add(ChunkPointer ptr){add(ptr.getValue());}
	public void add(long ptr){
		if(contains(ptr)) return;
		
		if(isEmpty()){
			start=ptr;
			end=ptr+1;
		}else{
			end=Math.max(end, ptr+1);
			start=Math.min(start, ptr);
		}
		size++;
		index.add(ptr);
		
		checkData();
	}
	private void checkData(){
		assert size()==longStream().count():
			size()+" "+stream().count();
		if(!isEmpty()){
			assert index.contains(start):
				start+" "+index.toString();
			assert index.contains(lastIndex()):
				lastIndex()+" "+index;
		}
	}
	
	public void remove(Chunk chunk)     {remove(chunk.getPtr());}
	public void remove(ChunkPointer ptr){remove(ptr.getValue());}
	public void remove(long ptr){
		checkEmpty();
		if(!contains(ptr)) return;
		
		if(rageSize()==1){
			clear();
		}else{
			assert index.contains(ptr);
			size--;
			index.removeLong(ptr);
			
			if(ptr==start) calcStart();
			if(ptr==lastIndex()) calcEnd();
		}
		checkData();
	}
	
	private void calcEnd(){
		end=index.getReverseLongIterator().next()+1;
	}
	private void calcStart(){
		start=index.getLongIterator().next();
	}
	
	public void removeFirst(){
		checkEmpty();
		if(rageSize()==1){
			clear();
		}else{
			assert index.contains(start);
			index.removeLong(start);
			size--;
			calcStart();
		}
		checkData();
	}
	
	public void removeLast(){
		checkEmpty();
		if(rageSize()==1){
			clear();
		}else{
			assert index.contains(lastIndex());
			index.removeLong(lastIndex());
			size--;
			calcEnd();
		}
		checkData();
	}
	
	public void clear(){
		start=-1;
		end=-1;
		size=0;
		index.clear();
	}
	
	public boolean contains(Chunk chunk)     {return contains(chunk.getPtr());}
	public boolean contains(ChunkPointer ptr){return contains(ptr.getValue());}
	public boolean contains(long ptr){
		var size=rageSize();
		if(size==0) return false;
		if(size==1) return ptr==start;
		
		var last=lastIndex();
		if(ptr<start||last<ptr) return false;
		if(ptr==start||ptr==last) return true;
		return index.contains(ptr);
	}
	
	public LongStream longStream(){
		return index.stream();
	}
	public Stream<ChunkPointer> stream(){
		return longStream().mapToObj(ChunkPointer::of);
	}
	
	public OptionalLong optionalMin(){
		return isEmpty()?OptionalLong.empty():OptionalLong.of(min());
	}
	public OptionalLong optionalMax(){
		return isEmpty()?OptionalLong.empty():OptionalLong.of(max());
	}
	public long min(){
		return start;
	}
	
	public long max(){
		return lastIndex();
	}
	
	@NotNull
	@Override
	public Iterator<ChunkPointer> iterator(){
		return stream().iterator();
	}
	
	private void checkEmpty(){
		if(isEmpty()) throw new IllegalStateException();
	}
	
	@Override
	public String toString(){
		return longStream().limit(50).mapToObj(Long::toString).collect(Collectors.joining(", ", "*[", size()>50?"...]":"]"));
	}
}
