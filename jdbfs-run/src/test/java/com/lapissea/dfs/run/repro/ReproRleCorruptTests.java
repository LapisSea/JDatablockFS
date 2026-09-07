package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.compress.Packer;
import com.lapissea.dfs.io.compress.RlePacker;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BUG-43 (rle-corrupt): {@link RlePacker} does not honour the {@link Packer#unpack} contract.
 *
 * <p><b>What the code is supposed to do.</b> {@code Packer.unpack(byte[])} is declared
 * {@code throws IOException} (Packer.java:7), so a corrupt / malformed packed payload is
 * expected to surface to callers as an {@link IOException}.
 *
 * <p><b>What it actually does.</b> {@link RlePacker}'s {@code Unpacker} performs no bounds or
 * sanity checks and lets raw <i>unchecked</i> exceptions escape (or silently returns wrong
 * bytes):
 * <ul>
 *   <li>RlePacker.java:162 (and :172, and via readLenInc :211) —
 *       {@code NumberSize.ordinal(dataNum)} is a bare {@code VALUES[dataNum]} lookup with no
 *       bounds check; a header byte whose {@code dataNum >= 8} throws
 *       {@link ArrayIndexOutOfBoundsException} inside {@code scanSize()}.</li>
 *   <li>RlePacker.java:216-227 — {@code copyIncByte}/{@code copyInc} (and {@code readLenInc}
 *       :210-215) read {@code packedData} past its end when a block points beyond the buffer,
 *       throwing {@link ArrayIndexOutOfBoundsException} (e.g. copyIncByte:217).</li>
 *   <li>RlePacker.java:153-194 — block lengths are trusted without validation, so a length
 *       inflated past the real payload is silently expanded into a longer, <i>wrong</i> output
 *       with no exception at all (the "silently wrong bytes" half of the reported defect).</li>
 * </ul>
 *
 * <p><b>Why the test fails.</b> Each corruption test packs valid data, mangles the packed
 * bytes, and asserts that {@code unpack} throws the <i>declared</i> {@link IOException}.
 * Because the implementation instead throws an unchecked {@link ArrayIndexOutOfBoundsException}
 * (or, for the silent case, returns wrong bytes with no exception), every assertion FAILS —
 * which is exactly the reported defect. {@link #sanityRoundTripWorks()} proves the
 * pack/unpack harness itself is correct, so the failures below are attributable to the bug,
 * not to the test.
 *
 * <p>Packed layout used throughout: {@code validData()} is 100×0x01 followed by 100×0x02.
 * Since 100 needs only one byte, each run encodes as a 3-byte REPEAT_1B block
 * ({@code (BYTE.ordinal()<<2)|REPEAT_1B = 0x04}, 1-byte length, 1-byte value), so
 * {@code pack(validData())} is exactly 6 bytes: {@code 04 64 01 04 64 02}.
 */
public class ReproRleCorruptTests {

    private final RlePacker packer = new RlePacker();

    /** 100×0x01 then 100×0x02 -> two 3-byte REPEAT blocks, packed = 6 bytes. */
    private static byte[] validData() {
        byte[] data = new byte[200];
        Arrays.fill(data, 0, 100, (byte) 0x01);
        Arrays.fill(data, 100, 200, (byte) 0x02);
        return data;
    }

    private static Throwable tryUnpack(Packer packer, byte[] input) {
        try {
            packer.unpack(input);
            return null; // no exception -> silent (wrong) output
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * Core assertion of the bug: unpack of corrupt data MUST throw the declared
     * {@link IOException}. It instead throws an unchecked exception (or returns wrong bytes
     * silently), so this fails today with the wrong exception type / no exception.
     */
    private static void assertUnpackFailsWithDeclaredIOException(String scenario, Packer packer, byte[] corrupted) {
        Throwable thrown = tryUnpack(packer, corrupted);
        // Bug mechanism: the declared contract is IOException; the actual throwable is an
        // unchecked ArrayIndexOutOfBoundsException, or null (silent wrong bytes).
        assertThat(thrown)
                .as("%s: unpack(corrupt) must throw the declared IOException, but threw: %s",
                        scenario,
                        thrown == null ? "<nothing - silent corruption>" : thrown.getClass().getName())
                .isInstanceOf(IOException.class);
    }

    /** Proves the harness: a valid payload round-trips cleanly (no exception, equal bytes). */
    @Test
    void sanityRoundTripWorks() {
        byte[] data = validData();
        byte[] packed = packer.pack(data);
        byte[] back = packer.unpack(packed);
        // Sanity: this PASSES and shows pack/unpack are otherwise correct, so the failures
        // in the other methods are due to the corrupt-input handling, not the test.
        assertThat(back).as("valid RLE round trip").containsExactly(data);
    }

    @Test
    void corruptHeader_dataNumOverflow_throwsDeclaredIOException() {
        byte[] packed = packer.pack(validData());
        // packed[0] is the first block header: (dataNum << 2) | blockId.
        // Forcing dataNum = 8 (byte 0x20, blockId = REPEAT_1B) makes
        // NumberSize.ordinal(8) hit VALUES[8] -> ArrayIndexOutOfBoundsException
        // (RlePacker.java:162), NOT the declared IOException.
        byte[] corrupted = packed.clone();
        corrupted[0] = (byte) 0x20;
        assertUnpackFailsWithDeclaredIOException("header dataNum>=8", packer, corrupted);
    }

    @Test
    void truncatedTail_throwsDeclaredIOException() {
        byte[] packed = packer.pack(validData());
        // Drop the final byte (the second block's repeat value). decompress then reads
        // packedData[packedPos++] past the end (RlePacker.java:217) -> AIOOBE, not IOException.
        byte[] corrupted = Arrays.copyOf(packed, packed.length - 1);
        assertUnpackFailsWithDeclaredIOException("truncated tail", packer, corrupted);
    }

    @Test
    void corruptMidStream_lengthInflated_throwsDeclaredIOException() {
        byte[] packed = packer.pack(validData());
        // packed[1] is the first block's 1-byte length (100). Inflating it to 255 is a
        // mid-stream corruption: the unpacker trusts the length, expands 255 copies of the
        // value, and silently returns 355 wrong bytes -- it does NOT throw the declared
        // IOException (RlePacker.java:153-194 trust the length with no validation).
        byte[] corrupted = packed.clone();
        corrupted[1] = (byte) 0xFF;
        assertUnpackFailsWithDeclaredIOException("mid-stream length inflated", packer, corrupted);
    }
}
