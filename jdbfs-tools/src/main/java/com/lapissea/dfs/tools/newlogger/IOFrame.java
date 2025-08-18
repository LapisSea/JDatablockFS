package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.util.function.UnsafeLongFunction;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;

public sealed interface IOFrame{
	
	@IOInstance.Order({"start", "size"})
	final class Range extends IOInstance.Managed<Range>{
		static{ allowFullAccess(MethodHandles.lookup()); }
		
		@IOValue
		@IODependency.VirtualNumSize
		@IOValue.Unsigned
		public final long start, size;
		
		public Range(long start, long size){
			this.start = start;
			this.size = size;
		}
		
		public long end(){ return start + size; }
	}
	
	@IOValue
	final class Full extends IOInstance.Managed<Full> implements IOFrame{
		static{ allowFullAccess(MethodHandles.lookup()); }
		
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private final long        uid;
		private final Blob        data;
		private final List<Range> writes;
		private final String      stacktrace;
		
		public Full(long uid, Blob data, List<Range> writes, String stacktrace){
			this.uid = uid;
			this.data = data;
			this.writes = List.copyOf(writes);
			this.stacktrace = stacktrace;
		}
		
		public String stacktrace(){ return stacktrace; }
		
		@Override
		public byte[] resolve(UnsafeLongFunction<IOFrame, IOException> frames) throws IOException{
			return data.readAll();
		}
		
		@Override
		public List<Range> writes(){ return writes; }
		@Override
		public long uid(){ return uid; }
	}
	
	@IOValue
	@IOInstance.Order({"parts", "partsBuff", "uid", "previousID", "newSize", "writes", "stacktrace"})
	final class Diff extends IOInstance.Managed<Diff> implements IOFrame{
		static{ allowFullAccess(MethodHandles.lookup()); }
		
		@IOValue
		public static final class Part extends IOInstance.Managed<Part>{
			@IODependency.VirtualNumSize
			@IOValue.Unsigned
			public final long  buffOffset;
			public final Range fileRange;
			
			public Part(long buffOffset, Range fileRange){
				this.buffOffset = buffOffset;
				this.fileRange = fileRange;
			}
		}
		
		public final List<Part> parts;
		public final Blob       partsBuff;
		public final long       uid;
		@IOValue.Unsigned
		public final long       previousID;
		public final int        newSize;
		
		private final List<Range> writes;
		private final String      stacktrace;
		
		public Diff(List<Part> parts, Blob partsBuff, long uid, long previousID, int newSize, List<Range> writes, String stacktrace){
			this.parts = List.copyOf(parts);
			this.partsBuff = partsBuff;
			this.uid = uid;
			this.previousID = previousID;
			this.newSize = newSize;
			this.writes = writes;
			this.stacktrace = stacktrace;
		}
		
		public String stacktrace(){ return stacktrace; }
		
		@Override
		public byte[] resolve(UnsafeLongFunction<IOFrame, IOException> frameGet) throws IOException{
			var prev = frameGet.apply(previousID);
			
			var bb = prev.resolve(frameGet);
			if(newSize != -1) bb = Arrays.copyOf(bb, newSize);
			
			try(var io = partsBuff.io()){
				for(Part part : parts){
					io.setPos(part.buffOffset)
					  .readFully(bb, Math.toIntExact(part.fileRange.start), Math.toIntExact(part.fileRange.size));
				}
			}
			
			return bb;
		}
		
		@Override
		public List<Range> writes(){ return writes; }
		@Override
		public long uid(){ return uid; }
	}
	
	byte[] resolve(UnsafeLongFunction<IOFrame, IOException> frames) throws IOException;
	
	List<Range> writes();
	long uid();
	String stacktrace();
}
