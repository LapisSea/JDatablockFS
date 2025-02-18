package com.lapissea.dfs.tools.newlogger;

import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public sealed interface IOFrame{
	
	final class Range extends IOInstance.Managed<Range>{
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
		private final Blob data;
		
		private final List<Range> writes;
		
		public Full(Blob data, List<Range> writes){
			this.data = data;
			this.writes = List.copyOf(writes);
		}
		@Override
		public byte[] resolve(IOList<IOFrame> frames) throws IOException{
			return data.readAll();
		}
		@Override
		public List<Range> writes(){ return writes; }
	}
	
	@IOValue
	final class Diff extends IOInstance.Managed<Diff> implements IOFrame{
		
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
		
		public static Diff of(IOInterface last, IOInterface src, long srcID, List<Range> writes) throws IOException{
			
			record MakePart(long fileOffset, ByteArrayOutputStream data){ }
			
			long newSize = -1;
			
			try(var lastIO = last.io(); var srcIO = src.io()){
				byte[] chunkA = new byte[1024];
				byte[] chunkB = new byte[1024];
				while(true){
					var readA = lastIO.read(chunkA);
					var readB = 0;
					while(readB<readA){
						var r = srcIO.read(chunkB, readB, readA - readB);
						if(r == -1) break;
						readB += r;
					}
					
					if(readA>readB){
					
					}
					
				}
			}
			
		}
		
		public final  List<Part> parts;
		private final Blob       partsBuff;
		
		public final long previousID;
		public final int  newSize;
		
		private final List<Range> writes;
		
		public Diff(List<Part> parts, Blob partsBuff, long previousID, int newSize, List<Range> writes){
			this.parts = List.copyOf(parts);
			this.partsBuff = partsBuff;
			this.previousID = previousID;
			this.newSize = newSize;
			this.writes = writes;
		}
		
		@Override
		public byte[] resolve(IOList<IOFrame> frames) throws IOException{
			var prev = frames.get(previousID);
			
			var bb = prev.resolve(frames);
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
	}
	
	byte[] resolve(IOList<IOFrame> frames) throws IOException;
	
	List<Range> writes();
}
