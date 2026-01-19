package com.lapissea.dfs.inspect.display.grid;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.IOException;
import java.util.Objects;

public record DataPos(ChunkPointer ptr, long offset){
	
	public record Sized(ChunkPointer ptr, DrawUtils.Range range){
		
		public static Sized ofRange(long offset, long size){
			return new Sized(ChunkPointer.NULL, DrawUtils.Range.fromSize(offset, size));
		}
		
		public Sized{
			Objects.requireNonNull(ptr);
			Objects.requireNonNull(range);
		}
		
		public RandomIO open(DataProvider dataProvider) throws IOException{
			if(ptr.isNull()){
				return dataProvider.getSource().ioAt(range.from());
			}else{
				return ptr.dereference(dataProvider).ioAt(range.from());
			}
		}
		
		public long absoluteFrom(DataProvider provider) throws IOException{
			if(ptr.isNull()){
				return range.from();
			}
			return new Reference(ptr, range.from()).calcGlobalOffset(provider);
		}
		
		public IterablePP<DrawUtils.Range> toAbsoluteRanges(DataProvider provider){
			if(ptr.isNull()){
				return Iters.of(range);
			}
			return DrawUtils.chainRangeResolve(provider, ptr.makeReference(), range.from(), range.size());
		}
		
		public long size(){
			return range.size();
		}
		
		@Override
		public String toString(){
			return "{" + ptr + " + " + range + "}";
		}
	}
	
	public static DataPos from(Reference ref){
		return new DataPos(ref.getPtr(), ref.getOffset());
	}
	
	public static DataPos absolute(long offset){
		return new DataPos(ChunkPointer.NULL, offset);
	}
	
	public DataPos{
		Objects.requireNonNull(ptr);
	}
	
	public RandomIO open(DataProvider dataProvider) throws IOException{
		if(ptr.isNull()){
			return dataProvider.getSource().ioAt(offset);
		}else{
			return ptr.dereference(dataProvider).ioAt(offset);
		}
	}
	
	public IterablePP<DrawUtils.Range> toAbsoluteRanges(DataProvider provider, long size){
		if(ptr.isNull()){
			return Iters.of(DrawUtils.Range.fromSize(offset, size));
		}
		return DrawUtils.chainRangeResolve(provider, ptr.makeReference(), offset, size);
	}
	public long toAbsoluteOffset(DataProvider provider) throws IOException{
		if(ptr.isNull()){
			return offset;
		}
		return new Reference(ptr, offset).calcGlobalOffset(provider);
	}
	
	public Sized withSize(long size){
		return new Sized(ptr, DrawUtils.Range.fromSize(offset, size));
	}
	
	@Override
	public String toString(){
		return "{" + ptr + " + " + offset + "}";
	}
}
