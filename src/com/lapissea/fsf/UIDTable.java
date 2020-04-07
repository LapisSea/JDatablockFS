package com.lapissea.fsf;

import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.collections.fixedlist.FixedLenList;
import com.lapissea.fsf.io.ContentInputStream;
import com.lapissea.fsf.io.ContentOutputStream;
import com.lapissea.fsf.io.serialization.FileObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;
import java.util.stream.LongStream;

public class UIDTable{
	
	private static class Range implements Comparable<Range>{
		
		private static class Head extends FileObject.FullLayout<Head> implements FixedLenList.ElementHead<Head, Range>{
			
			private static final ObjectDef<Head> LAYOUT=new SingleEnumDef<>(NumberSize.class, h->h.size, (h, v)->h.size=v);
			
			private NumberSize size;
			
			public Head(){
				this(NumberSize.BYTE);
			}
			
			public Head(NumberSize size){
				super(LAYOUT);
				this.size=size;
			}
			
			@Override
			public Head copy(){
				return new Head(size);
			}
			
			private NumberSize calcSize(Range e){
				return NumberSize.bySize(Math.max(e.start, e.end));
			}
			
			@Override
			public boolean willChange(Range element){
				return calcSize(element).greaterThan(size);
			}
			
			@Override
			public void update(Range element){
				size=NumberSize.bySize(Math.max(element.start, element.end));
			}
			
			@Override
			public int getElementSize(){
				return size.bytes;
			}
			
			@Override
			public Range newElement(){
				return new Range();
			}
			
			@Override
			public void readElement(ContentInputStream src, Range dest) throws IOException{
				dest.start=size.read(src);
				dest.end=size.read(src);
			}
			
			@Override
			public void writeElement(ContentOutputStream dest, Range src) throws IOException{
				size.write(dest, src.start);
				size.write(dest, src.end);
			}
		}
		
		long start;
		long end;
		
		Range(){ }
		
		Range(long start, long end){
			this.start=start;
			this.end=end;
		}
		
		public boolean isEmpty(){
			return start==end;
		}
		
		@Override
		public int compareTo(Range o){
			return Long.compare(start, o.start);
		}
		
		public Range copy(){
			return new Range(start, end);
		}
		
		public LongStream rangeStream(){
			return LongStream.rangeClosed(start, end);
		}
		
		public boolean idInside(long id){
			return id >= start&&id<=end;
		}
		
		@Override
		public boolean equals(Object o){
			if(this==o) return true;
			if(!(o instanceof Range)) return false;
			Range range=(Range)o;
			return start==range.start&&
			       end==range.end;
		}
		
		@Override
		public int hashCode(){
			int result=1;
			result=31*result+Long.hashCode(start);
			result=31*result+Long.hashCode(end);
			return result;
		}
	}
	
	private IOList<Range> ranges;
	private Range         possibleRange;
	
	public UIDTable(Chunk tableData, Range possibleRange) throws IOException{
		this.possibleRange=possibleRange.copy();
		ranges=new FixedLenList<>(Range.Head::new, tableData);
	}
	
	private void bruteForceRebuild() throws IOException{
		if(ranges.isEmpty()) return;
		
		var newRange=new ArrayList<Range>();
		ranges.stream()
		      .flatMapToLong(Range::rangeStream)
		      .distinct()
		      .forEach(id->allocateId(newRange, id));
		
		if(newRange.equals(ranges)) return;
		
		//copy on write
		var head    =new Range.Head(NumberSize.bySize(newRange.stream().mapToLong(r->r.end).max().orElseThrow()));
		var capacity=FixedLenList.calcPos(head, newRange.size());
		
		var oldData=ranges.getData();
		var newData=oldData.header.aloc(capacity, true);
		
		var newRanges=new FixedLenList<>(Range.Head::new, newData);
		newRanges.addAll(newRange);
		
		ranges=newRanges;
		oldData.transparentChainRestart(newData);
		
	}
	
	private static void allocateId(List<Range> newRange, long id){
		newRange.add(new Range());
	}
	
	public void freeId(INumber id) throws IOException{
		freeId(id.getValue());
	}
	
	public void freeId(long id) throws IOException{
		if(ranges.size()>1){
			var last=ranges.getElement(0);
			for(int i=1;i<ranges.size();i++){
				var next=ranges.getElement(i);
				
				if(last.end+1==next.start){
					last.end=next.end;
					int lastIndex=i-1;
					ranges.setElement(lastIndex, last);
					ranges.setElement(lastIndex, last);
					
				}
				last=next;
			}
		}
		
		for(int i=0;i<ranges.size();i++){
			var range=ranges.getElement(i);
			ok:
			{
				if(range.end+1==id) range.end++;
				else if(range.start-1==id) range.start--;
				else break ok;
				
				ranges.setElement(i, range);
				return;
			}
		}
	}
	
	public <T extends INumber> T requestId(LongFunction<T> idMaker) throws IOException{
		return idMaker.apply(requestId());
	}
	
	public long requestId() throws IOException{
		long id=findUnallocatedId(possibleRange, ranges);
		allocateId(ranges, id);
		
		return id;
	}
	
	private long findUnallocatedId(Range possibleRange, IOList<Range> ranges) throws IOException{
		if(ranges.isEmpty()) return possibleRange.start;
		for(int i=0;i<ranges.size();i++){
			Range range=ranges.getElement(i);
			
		}
		return 0;
	}
}
