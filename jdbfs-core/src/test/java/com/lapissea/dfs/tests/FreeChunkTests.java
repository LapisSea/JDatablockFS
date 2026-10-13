package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.core.chunk.PhysicalExtentWalker;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

public class FreeChunkTests{
	@Test
	public void everyFreeTagRejectsBeforeReadingPayload() throws IOException{
		var source   = MemoryData.of(new byte[32]);
		var provider = DataProvider.newVerySimpleProvider(source);
		var generic  = new StandardStructPipe<>(Chunk.STRUCT, StagedInit.STATE_DONE);
		int freeTags = 0;
		for(int tag = 0; tag<256; tag++){
			if(!FreeChunk.isFreeTag(tag)) continue;
			freeTags++;
			assertFalse(BitFieldMerger.areIntegrityBitsValid(tag, 8, 6));
			for(int payload : new int[]{0, 1, 0xFF, 0x84}){
				try(var io = source.io()){
					io.setPos(1);
					io.writeInt1(tag);
					for(int i = 0; i<25; i++) io.writeInt1(payload);
				}
				for(var pipe : List.of(Chunk.PIPE, generic)){
					var chunk = new Chunk(provider, ChunkPointer.of(1), NumberSize.VOID, 0, 0, NumberSize.LONG, ChunkPointer.NULL);
					try(var io = source.ioAt(1)){
						assertThrows(IOException.class, () -> pipe.read(provider, io, chunk, null));
						assertEquals(io.getPos(), 2L, "Reader must reject at the tag before decoding payload");
					}
				}
				assertThrows(IOException.class, () -> Chunk.readChunk(provider, ChunkPointer.of(1)));
			}
		}
		assertEquals(freeTags, 15);
	}
	@Test
	public void roundTripEverySmallSizeAndEncodingBoundary() throws IOException{
		for(long total : new long[]{9, 10, 11, 12, 13, 14, 15, 16, 17, 254, 255, 256, 257, 65535, 65536, 65537}){
			var source = MemoryData.of(new byte[(int)(1 + total)]);
			for(long link : new long[]{0, 2, 255, 65536, Long.MAX_VALUE}){
				var extent = new FreeChunk(ChunkPointer.of(1), total, ChunkPointer.of(link));
				extent.write(source);
				assertEquals(FreeChunk.read(source, extent.getPtr()), extent);
				assertEquals(extent.dataEnd(), 1 + total);
				assertTrue(extent.getHeaderSize()<=total);
			}
		}
	}
	@Test
	public void allSizeWidthsUseRecognizedRejectingTags(){
		for(long total : new long[]{9, 10, 16, 17, 256, 65536, 1L<<24, 1L<<32, 1L<<40, 1L<<48, Long.MAX_VALUE - 1}){
			var extent = new FreeChunk(ChunkPointer.of(1), total, ChunkPointer.NULL);
			assertTrue(FreeChunk.isFreeTag(extent.headerTag()));
			assertFalse(BitFieldMerger.areIntegrityBitsValid(extent.headerTag(), 8, 6));
		}
	}
	@Test
	public void physicalWalkingPreservesTinyFreeBoundaries() throws IOException{
		var source = MemoryData.of(new byte[20]);
		var first  = new FreeChunk(ChunkPointer.of(1), 9, ChunkPointer.of(10));
		var second = new FreeChunk(ChunkPointer.of(10), 10, ChunkPointer.NULL);
		first.write(source);
		second.write(source);
		var walked = new PhysicalExtentWalker(DataProvider.newVerySimpleProvider(source), first.getPtr()).toList();
		assertEquals(walked, List.of(first, second));
	}
	@Test
	public void rejectsInvalidExtents() throws IOException{
		assertThrows(IllegalArgumentException.class, () -> new FreeChunk(ChunkPointer.of(1), 8, ChunkPointer.NULL));
		assertThrows(IllegalArgumentException.class, () -> new FreeChunk(ChunkPointer.of(1), Long.MAX_VALUE, ChunkPointer.NULL));
		assertThrows(IllegalArgumentException.class, () -> new FreeChunk(ChunkPointer.of(1), 9, ChunkPointer.of(1)));
		var source = MemoryData.of(new byte[10]);
		new FreeChunk(ChunkPointer.of(1), 100, ChunkPointer.NULL).write(source);
		assertThrows(IOException.class, () -> FreeChunk.read(source, ChunkPointer.of(1)));
	}
}
