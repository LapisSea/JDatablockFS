package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.RandomIOReadOnly;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * BUG-45 (roflush-close) — reproduction attempt.
 *
 * What the code should do:
 *   RandomIOReadOnly.flush() should flush buffered data and the stream must
 *   remain usable afterwards: reads after flush must keep working.
 *
 * What the code actually does:
 *   RandomIOReadOnly.flush() (jdbfs-core .../io/RandomIOReadOnly.java:52-55)
 *   delegates to io.close() — a real close(), not a flush.
 *
 * Why the reported failure mode does NOT manifest (why these tests pass):
 *   RandomIOReadOnly only wraps a RandomIO (RandomIOReadOnly.java:42). The
 *   only RandomIO obtainable directly from a file-backed IOInterface
 *   (FileRandomAccessData / FileMemoryMappedData) is
 *   CursorIOData.CursorRandomIO, produced by IOInterface.io()/readOnlyIO().
 *   CursorRandomIO.close() is a NO-OP (CursorIOData.java:110) — flush() is a
 *   no-op too (CursorIOData.java:113). The file handle/lock/mapping is owned
 *   by the IOInterface itself, not by the RandomIO session: e.g.
 *   RandomIO.readAll()/ioAt() close their session on every use
 *   (try(var io = io())) and the data stays usable afterwards. Consequently
 *   no RandomIO.close() reachable through RandomIOReadOnly ever reaches
 *   FileRandomAccessData.close()/FileMemoryMappedData.close(), so flush()
 *   releases no lock and tears down no mapping.
 *
 *   The only RandomIO whose close() has a real side effect on these paths is
 *   ChunkChainIO (ChunkChainIO.close(): cursor.syncStruct() + source.close()
 *   [no-op] + mem.notifyEnd(this)). It never closes the file; its only
 *   observable effect is per-thread bookkeeping — closing the same chain
 *   twice throws IllegalStateException from
 *   PersistentMemoryManager.notifyEnd (documented in test 4).
 *
 * Each test implements a genuine trigger strategy from the BUG-45 entry:
 * file-backed source over a temp file, wrapped in a RandomIOReadOnly,
 * read OK, flush(), read again. If the reported defect were present, the
 * read-after-flush step would throw (closed file / released mapping) or
 * return corrupt data and these tests would FAIL at that assertion.
 */
public class ReproRoFlushCloseTests{

	private static final byte[] PAYLOAD = makePayload(8192);
	
	private static byte[] makePayload(int size){
		var out = new byte[size];
		for(int i=0; i<size; i++) out[i] = (byte)(i*31 + 7);
		return out;
	}
	
	private static File tempFile() throws IOException{
		var f = File.createTempFile("roflush-repro", ".bin");
		f.deleteOnExit();
		return f;
	}
	
	/** Reads everything from pos 0 of a RandomIO. */
	private static byte[] readAll(RandomIO ro) throws IOException{
		ro.setPos(0);
		final int size = Math.toIntExact(ro.getSize());
		final var buf = new byte[size];
		int off = 0;
		while(off<size){
			final int r = ro.read(buf, off, size-off);
			if(r<0) throw new IOException("Unexpected EOF at "+off+"/"+size);
			off += r;
		}
		return buf;
	}
	
	/** Strategy 1 (literal entry scenario): FileRandomAccessData over a temp
	 *  file, wrapped directly in a RandomIOReadOnly. */
	@Test
	public void flushThenRead_directWrap_fileRandomAccess() throws IOException{
		final var data = new FileRandomAccessData(tempFile(), FileRandomAccessData.Mode.READ_WRITE);
		try{
			try(var io = data.io()){
				io.write(PAYLOAD, 0, PAYLOAD.length);
			}
			
			final var ro = new RandomIOReadOnly(data.io()); // the wrap from the bug entry
			
			assertThat(readAll(ro)).as("read before flush").isEqualTo(PAYLOAD);
			
			ro.flush(); // RandomIOReadOnly.java:52-55 -> io.close()
			
			// If the reported bug existed, this read would fail (stream destroyed /
			// file lock released / mapping torn down).
			assertThat(readAll(ro)).as("read after flush").isEqualTo(PAYLOAD);
			
			// A brand-new session on the same data must also still work.
			assertThat(data.readAll()).as("fresh session after flush").isEqualTo(PAYLOAD);
		}finally{
			data.close();
		}
	}
	
	/** Strategy 2: FileMemoryMappedData over a temp file, through the
	 *  production entry point IOInterface.readOnlyIO(). */
	@Test
	public void flushThenRead_readOnlyIO_entry_fileMemoryMapped() throws IOException{
		final var data = new FileMemoryMappedData(tempFile());
		try{
			try(var io = data.io()){
				io.write(PAYLOAD, 0, PAYLOAD.length);
			}
			
			final var ro = data.readOnlyIO(); // -> new RandomIOReadOnly(io()) (RandomIO.java:145-149)
			
			assertThat(readAll(ro)).as("read before flush").isEqualTo(PAYLOAD);
			
			ro.flush();
			
			assertThat(readAll(ro)).as("read after flush").isEqualTo(PAYLOAD);
			assertThat(data.readAll()).as("fresh session after flush").isEqualTo(PAYLOAD);
		}finally{
			data.close();
		}
	}
	
	/** Strategy 3: real Cluster over file-backed data; the RandomIOReadOnly
	 *  wraps a ChunkChainIO (the "plausible path" ChunkChainIO.java:269). */
	@Test
	public void flushThenRead_cluster_chunkReadOnlyIO() throws IOException{
		final var data = new FileRandomAccessData(tempFile(), FileRandomAccessData.Mode.READ_WRITE);
		try{
			final var cl = Cluster.init(data);
			
			// 4096 < PAYLOAD.length, so the data spans a linked chain of chunks.
			final var chunk = AllocateTicket.bytes(4096).submit(cl);
			try(var io = chunk.io()){
				io.write(PAYLOAD, 0, PAYLOAD.length);
			}
			
			final var ro = chunk.readOnlyIO(); // -> new RandomIOReadOnly(new ChunkChainIO(chunk))
			
			assertThat(readAll(ro)).as("chunk read before flush").isEqualTo(PAYLOAD);
			
			ro.flush(); // -> ChunkChainIO.close(): syncStruct + source.close (no-op) + mem.notifyEnd
			
			assertThat(readAll(ro)).as("chunk read after flush").isEqualTo(PAYLOAD);
			
			// A fresh chain over the same chunks must also still work.
			try(var io = chunk.io()){
				assertThat(readAll(io)).as("fresh chain after flush").isEqualTo(PAYLOAD);
			}
		}finally{
			data.close();
		}
	}
	
	/** Documents the closest observable consequence of the defect: on the
	 *  chunk path flush() really does perform a close() (the second close of
	 *  the same chain trips PersistentMemoryManager.notifyEnd bookkeeping) —
	 *  but it never touches the file, so reads keep working (see test 3). */
	@Test
	public void flushReallyCloses_chainBookkeeping() throws IOException{
		final var data = new FileRandomAccessData(tempFile(), FileRandomAccessData.Mode.READ_WRITE);
		try{
			final var cl    = Cluster.init(data);
			final var chunk = AllocateTicket.bytes(4096).submit(cl);
			try(var io = chunk.io()){
				io.write(PAYLOAD, 0, PAYLOAD.length);
			}
			final var ro = chunk.readOnlyIO();
			
			assertThat(readAll(ro)).isEqualTo(PAYLOAD);
			
			ro.flush(); // close #1 -> ChunkChainIO.close() -> mem.notifyEnd (ok)
			assertThat(readAll(ro)).as("chunk read after flush").isEqualTo(PAYLOAD);
			
			// close #2 on the same chain: notifyEnd finds an empty stack and throws.
			assertThatThrownBy(() -> ro.close())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("should have ended");
			
			// ...yet the file-backed stream itself is untouched.
			try(var io = chunk.io()){
				assertThat(readAll(io)).as("fresh chain after double close").isEqualTo(PAYLOAD);
			}
		}finally{
			data.close();
		}
	}
}
