package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-08 (bitin-64): BitInputStream.readBits(64) silently corrupts subsequent reads.
 *
 * What the code is supposed to do:
 * BitInputStream holds source bytes in a 64-bit {@code buffer}. readBits(n) with
 * n <= bufferedBits returns the low n bits and then calls advance(n) to discard them:
 * {@code buffer >>>= n; bufferedBits -= n;} (jdbfs-core .../io/bit/BitInputStream.java:77-81).
 * For n = 64 that should clear the whole buffer.
 *
 * What actually happens:
 * The JVM masks long shift distances with 0x3F (JLS 15.19), so the
 * {@code buffer >>>= 64} at BitInputStream.java:79 is a NO-OP (== buffer >>>= 0).
 * After readBits(64) every stale bit stays in {@code buffer} while {@code bufferedBits}
 * is decremented to 0 or 1. On the NEXT read, prepareBits (BitInputStream.java:27-51,
 * the {@code buffer |= source... << bufferedBits} at :44 and :47) ORs fresh source bytes
 * over the stale 1-bits. Every stale 1-bit that corresponds to a fresh 0-bit flips the
 * result: silent data corruption.
 * The writer is not affected: BitOutputStream caps its own buffer at 63 bits
 * (MAX_BITS, BitOutputStream.java:14, guard at :30) so it never advances by 64.
 *
 * Why this test fails:
 * Bit layout (192 bits, three 8-byte words, written with BitOutputStream):
 *   word1 = bits   0..63  = all 1
 *   word2 = bits  64..127 = all 1   (become the stale bits left in the buffer)
 *   word3 = bits 128..191 = all 0   (fresh data that must come back uncorrupted)
 * Read sequence through BitInputStream:
 *   1) readBits(63) -> bits  0..62, leaves bufferedBits = 1 (inside the 1..63 window)
 *   2) readBits(64) -> bits 63..126: the value itself is still correct, but advance(64)
 *      is a no-op, so all 65 stale bits remain in the buffer with bufferedBits = 1
 *   3) readBits(63) -> should be bits 127..189 = 1 (only bit 127, the MSB of word2, is set);
 *      the fresh zero-bytes of word3 are OR-ed over word2's stale 1-bits -> all 63 bits set
 *   => assertion 3 fails: (1L<<63)-1 != 1
 */
public class ReproBitIn64Tests{

	@Test
	void readBits64WithPartialBufferCorruptsTheNextRead() throws IOException{
		var buff = new byte[24];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
			out.writeBits(0xFFFFFFFFFFFFFFFFL, 64); // word1: bits   0..63  all 1
			out.writeBits(0xFFFFFFFFFFFFFFFFL, 64); // word2: bits  64..127 all 1 (later the stale bits)
			out.writeBits(0L, 64);                  // word3: bits 128..191 all 0 (fresh data)
			out.requireWritten(192);
		}

		// expectedBits = 0 ("unknown length", same style as BitField.java:94) - the bug is
		// in advance() and is independent of the expectedBits value
		try(var in = new BitInputStream(new ContentInputStream.BA(buff), 0)){

			var r1 = in.readBits(63);
			assertThat(r1).as("read #1: 63 bits").isEqualTo((1L << 63) - 1);
			// left exactly 1 buffered bit: bufferedBits is now in the 1..63 window the bug needs

			var r2 = in.readBits(64);
			assertThat(r2).as("read #2: 64 bits (the 64-bit read itself is still correct)")
			               .isEqualTo(-1L);
			// BUG: advance(64) at BitInputStream.java:79 is `buffer >>>= 64`, masked by the
			// JVM to `buffer >>>= 0` (no-op): all 65 stale bits remain in the buffer even
			// though bufferedBits is now 1

			var r3 = in.readBits(63);
			// should be bits 127..189: only bit 127 (MSB of word2) is 1 -> expected 1.
			// prepareBits ORs word3's zero-bytes onto word2's stale 1-bits
			// (BitInputStream.java:44/:47 `buffer |= fresh << bufferedBits`), so all 63
			// bits come back set:
			assertThat(r3).as("read #3: 63 bits right after the 64-bit read - corrupted by stale buffer bits")
			               .isEqualTo(1L);

			var r4 = in.readBits(2);
			assertThat(r4).as("read #4: 2 trailing bits").isEqualTo(0L);
		}
	}
}
