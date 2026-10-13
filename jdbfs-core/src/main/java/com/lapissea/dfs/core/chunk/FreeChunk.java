package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedObject;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;

import java.io.IOException;
import java.util.Objects;

/**
 * {@link FreeChunk} is distinctly different (and exclusive) to {@link Chunk}.<br>
 * A {@link Chunk} is a full header with content size and user data, {@link FreeChunk} is simply some
 * unallocated data that may be reused by an allocation. The historical usage was just to have a {@link Chunk} for both free, and
 * used regions but there is an edge case where a pointer of 8 bytes in a chunk of 10 bytes total size is unrepresentable. <br>
 * <br>
 * A chunk can never read a free chunk, their encodings are deliberately incompatible.
 */
public record FreeChunk(ChunkPointer ptr, long totalSize, ChunkPointer nextPtr) implements PhysicalExtent{
	
	private static final int   SMALL_COUNT = 8;
	private static final int   TAG_COUNT   = SMALL_COUNT + NumberSize.values().length - 1;
	private static final int[] TAGS        = new int[TAG_COUNT];
	
	static{
		for(int i = 0; i<TAGS.length; i++){
			// Change one integrity bit, preserving a unique payload for each free tag.
			TAGS[i] = (int)(i|BitFieldMerger.calcIntegrityBits(i, 2, 6))^0x40;
		}
	}
	
	public FreeChunk{
		Objects.requireNonNull(ptr);
		Objects.requireNonNull(nextPtr);
		if(ptr.isNull()) throw new IllegalArgumentException("A free extent cannot start at the null pointer");
		if(totalSize<Chunk.minSafeSize()) throw new IllegalArgumentException("Free extent smaller than minimum: " + totalSize);
		if(ptr.getValue()>Long.MAX_VALUE - totalSize) throw new IllegalArgumentException("Free extent end overflow");
		if(nextPtr.equals(ptr)) throw new IllegalArgumentException("Free extent links to itself");
	}
	
	private static int tagIndex(int tag){
		for(int i = 0; i<TAGS.length; i++) if(TAGS[i] == tag) return i;
		return -1;
	}
	public static boolean isFreeTag(int tag){ return tagIndex(tag)>=0; }
	public int headerTag()                  { return TAGS[encodingIndex()]; }
	
	private int encodingIndex(){
		return totalSize<=16? (int)(totalSize - 9) : SMALL_COUNT + NumberSize.bySize(totalSize).ordinal() - 1;
	}
	@Override
	public ChunkPointer getPtr(){ return ptr; }
	public ChunkPointer getNextPtr(){ return nextPtr; }
	
	@Override
	public int getHeaderSize(){ return 9 + (totalSize<=16? 0 : NumberSize.bySize(totalSize).bytes); }
	@Override
	public long dataEnd(){ return ptr.getValue() + totalSize; }
	public long dataStart()  { return ptr.getValue() + getHeaderSize(); }
	public long getCapacity(){ return totalSize - getHeaderSize(); }
	
	public FreeChunk withNextPtr(ChunkPointer next){
		return new FreeChunk(ptr, totalSize, next);
	}
	
	public void write(DataProvider provider) throws IOException{ write(provider.getSource()); }
	public void write(IOInterface source) throws IOException{
		var header = new byte[getHeaderSize()];
		header[0] = (byte)headerTag();
		if(totalSize>16) NumberSize.bySize(totalSize).write(header, 1, totalSize);
		NumberSize.LONG.write(header, header.length - Long.BYTES, nextPtr);
		try(var io = source.ioAt(ptr.getValue())){ io.write(header); }
	}
	
	public static FreeChunk read(DataProvider provider, ChunkPointer ptr) throws IOException{ return read(provider.getSource(), ptr); }
	public static FreeChunk read(IOInterface source, ChunkPointer ptr) throws IOException{
		try(var io = source.ioAt(ptr.getValue())){
			int index = tagIndex(io.readUnsignedInt1());
			if(index<0){
				throw new MalformedObject("Not a FreeChunk at " + ptr);
			}
			
			long total = index<SMALL_COUNT? 9 + index : NumberSize.ordinal(index - SMALL_COUNT + 1).read(io);
			
			ChunkPointer next;
			try{
				next = ChunkPointer.read(NumberSize.LONG, io);
			}catch(IllegalArgumentException e){
				throw new MalformedObject(e);
			}
			
			try{
				var chunk = new FreeChunk(ptr, total, next);
				if(chunk.encodingIndex() != index){
					throw new MalformedObject("Noncanonical FreeChunk size encoding at " + ptr);
				}
				if(chunk.dataEnd()>io.getSize()){
					throw new MalformedObject("FreeChunk extends beyond the file at " + ptr);
				}
				return chunk;
			}catch(IllegalArgumentException e){
				throw new MalformedObject(e);
			}
		}
	}
}
