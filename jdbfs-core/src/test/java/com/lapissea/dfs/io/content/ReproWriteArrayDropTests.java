package com.lapissea.dfs.io.content;

import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

/**
 * BUG-48 writearray-drop
 *
 * <p>What the code is supposed to do:
 * {@code ContentWriter.writeTicket(amount).submit()} creates a {@code ContentWriter.BufferTicket.WriteArrayBuffer}
 * (ContentWriter.java:285-335): a scratch buffer of {@code amount} bytes into which the caller writes a
 * pre-sized blob. Every byte handed to the buffer must actually be stored, and {@code close()}
 * (ContentWriter.java:292-304) must flush exactly the stored bytes to the target writer.
 *
 * <p>What it actually does:
 * {@code WriteArrayBuffer.writeWord(long, int)} (ContentWriter.java:312-318) only stores the word when
 * {@code count + len <= buf.length} (:313). When the word does not fit, the {@code WordIO.setWord} call is
 * skipped, but {@code count += len} still runs (:316) — the bytes are silently dropped while the write
 * position advances past them. On a plain ticket ({@code writeTicket(amount)}, errorOnMismatch == null) the
 * {@code earlyCheck()} at :317 cannot catch this, it only fires for tickets built with
 * {@code requireExact()}. All current production callers (StructPipe.java:788, StructPipe.java:863,
 * CollectionAdapter.java:258) use {@code requireExact()}, so the defect is latent — this test drives the
 * buffer through the plain-ticket path directly.
 *
 * <p>Why the test fails:
 * With a 4-byte initial buffer, the first 4-byte word is stored and the second is dropped, yet
 * {@code count} ends at 8. {@code close()} runs {@code onFinish(count, buf)} (ContentWriter.java:296-298) —
 * which exposes the internal buffer — and then flushes {@code target.write(buf, 0, count)}
 * (ContentWriter.java:302) with count=8 over the 4-byte array:
 * 1) the stored content is only the first 4 bytes instead of the 8 written (silent data loss) →
 *    the content assertion fails, and
 * 2) the out-of-bounds flush throws IndexOutOfBoundsException on the never-written region.
 */
public class ReproWriteArrayDropTests{

	/**
	 * A sink that bounds-checks write(byte[], off, len) exactly like standard sinks do
	 * (ByteArrayOutputStream#write(byte[],int,int), and the array access behind
	 * ContentOutputStream / ChunkChainIO): writing past the end of the supplied array is an error.
	 */
	private static final class CollectingWriter implements ContentWriter{
		final byte[] sink = new byte[4096];
		int sinkLen = 0;

		@Override
		public void write(int b){
			sink[sinkLen++] = (byte)b;
		}

		@Override
		public void write(byte[] b, int off, int len){
			if(off < 0 || len < 0 || off + len > b.length){
				throw new IndexOutOfBoundsException("write(off=" + off + ", len=" + len + ") exceeds array length " + b.length);
			}
			System.arraycopy(b, off, sink, sinkLen, len);
			sinkLen += len;
		}
	}

	@Test
	void overflowingWriteWordIsSilentlyDroppedThenCloseFails() throws IOException{
		var target = new CollectingWriter();
		byte[][] capturedBuf = new byte[1][];
		int[] capturedCount  = new int[1];

		// plain ticket (NOT requireExact) → errorOnMismatch == null → earlyCheck() at :325-329 is inert
		var ticket = target.writeTicket(4)
			// close() invokes onFinish(count, buf) BEFORE flushing (ContentWriter.java:296-298):
			// a production-API seam that exposes the buffer's internal state
			.onFinish((count, buf) -> {
				capturedBuf[0]   = buf;
				capturedCount[0] = count;
			});
		var buffer = ticket.submit(); // internal buf = new byte[4], count = 0

		buffer.writeWord(0x04030201L, 4); // fits: count 0 + 4 <= buf.length 4 → WordIO.setWord stores it
		buffer.writeWord(0x08070605L, 4); // overflows: count 4 + 4 > 4 → setWord skipped (:313-315), count += 4 (:316)

		// close() → onFinish captures the buffer, then target.write(buf, 0, 8) over the 4-byte array
		IndexOutOfBoundsException closeFailure = null;
		try{
			buffer.close();
		}catch(IndexOutOfBoundsException e){
			closeFailure = e;
		}

		// second half of the reported mechanism: close() blows up on the never-written region
		// (count advanced to 8 but the buffer only ever held 4 bytes)
		assertNotNull(closeFailure,
			"close() flushed count=8 bytes over the 4-byte buffer (ContentWriter.java:302) — the drop made the flush out of bounds");

		// first half: the write position advanced even though the bytes were never stored
		assertEquals(capturedCount[0], 8, "count advanced past the buffer's capacity");

		// little-endian words (WordIO.setWord → BBView.writeInt4): expected stored bytes 01 02 03 04 | 05 06 07 08
		byte[] expected = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

		// BUG: the second word was never stored (ContentWriter.java:313-315 skips WordIO.setWord when the
		// word does not fit) — the buffer still holds only the first 4 bytes: silent data loss before close()
		assertThat(capturedBuf[0])
			.as("buffer contents after 8 written bytes: the overflowing word was silently dropped")
			.containsExactly(expected);
	}

	@Test
	void singleWordLargerThanBufferIsEntirelyDropped() throws IOException{
		var target = new CollectingWriter();
		byte[][] capturedBuf = new byte[1][];
		int[] capturedCount  = new int[1];

		var ticket = target.writeTicket(4)
			.onFinish((count, buf) -> {
				capturedBuf[0]   = buf;
				capturedCount[0] = count;
			});
		var buffer = ticket.submit(); // internal buf = new byte[4]

		// one 8-byte word into a 4-byte buffer: 0 + 8 > 4 → the WHOLE word is skipped, count += 8
		buffer.writeWord(0x0807060504030201L, 8);

		IndexOutOfBoundsException closeFailure = null;
		try{
			buffer.close();
		}catch(IndexOutOfBoundsException e){
			closeFailure = e;
		}

		// close() failed on the never-written region (count=8 over a 4-byte buffer)
		assertNotNull(closeFailure, "close() flushed count=8 bytes over the 4-byte buffer (ContentWriter.java:302)");

		// position says 8 bytes were written, but the buffer holds none of them
		assertEquals(capturedCount[0], 8, "count advanced past the buffer's capacity");

		byte[] expected = { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };

		// BUG: nothing was stored at all — the entire word was dropped (ContentWriter.java:313-315)
		assertThat(capturedBuf[0])
			.as("buffer contents after one 8-byte word into a 4-byte buffer: the word was silently dropped")
			.containsExactly(expected);
	}
}
