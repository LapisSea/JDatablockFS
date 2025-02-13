package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SuppressWarnings("Convert2MethodRef")
public class ChunkChainTests{
	
	private static final class PositionedIO{
		final Chunk src;
		int pos;
		private PositionedIO(Chunk src){ this.src = src; }
		
		ChunkChainIO io() throws IOException{
			var p  = pos++;
			var io = src.io();
			io.write(new byte[p]);
			return io;
		}
	}
	
	Chunk testChunk() throws IOException{ return testChunk(64); }
	Chunk testChunk(int capacity) throws IOException{
		var mem = Cluster.emptyMem();
		return AllocateTicket.bytes(capacity).submit(mem);
	}
	
	@BeforeClass
	void before() throws IOException{ testChunk(); }
	
	@Test
	void doubleClose() throws IOException{
		var chunk = testChunk();
		
		var outerIo = chunk.io();
		
		var io = chunk.io();
		
		io.writeInt4(123);
		
		io.close();
		assertThatThrownBy(() -> {
			io.close();
		}).isInstanceOf(IllegalStateException.class)
		  .hasMessage("Chain was already closed!");
		
		outerIo.close();
	}
	@Test
	void doubleCloseTop() throws IOException{
		var chunk = testChunk();
		
		var io = chunk.io();
		
		io.writeInt4(123);
		
		io.close();
		assertThatThrownBy(() -> {
			io.close();
		}).isInstanceOf(IllegalStateException.class)
		  .hasMessage("There is nothing that should have ended!");
		
		assertThatThrownBy(() -> {
			io.close();
		}).as("Should throw IllegalStateException because the chain stack should be empty.")
		  .isInstanceOf(IllegalStateException.class)
		  .hasMessage("There is nothing that should have ended!");
	}
	
	@Test
	void wrongOrderClose() throws IOException{
		var chunk   = testChunk();
		var ioMaker = new PositionedIO(chunk);
		
		var before0 = ioMaker.io();
		var before1 = ioMaker.io();
		var before2 = ioMaker.io();
		var before3 = ioMaker.io();
		
		var badIo = ioMaker.io();
		badIo.writeInt1(123);
		
		var after0 = ioMaker.io();
		var after1 = ioMaker.io();
		
		assertThatThrownBy(() -> {
			badIo.close();
		}).isInstanceOf(IllegalStateException.class)
		  .hasMessage("Chain is closed in wrong order! There is 2 open chains ahead!");
		
		after1.close();
		after0.close();
		
		badIo.close();
		
		before3.close();
		before2.close();
		before1.close();
		before0.close();
	}
	
}
