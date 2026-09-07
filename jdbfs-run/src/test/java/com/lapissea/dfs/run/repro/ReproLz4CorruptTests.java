package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.type.field.annotations.IOCompression;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

/**
 * BUG-44 lz4-corrupt
 *
 * What the code is supposed to do:
 *   Packer.unpack is declared `throws IOException` (jdbfs-core/src/main/java/com/lapissea/dfs/io/compress/Packer.java:7)
 *   so that corrupt/malformed packed data is reported to callers as the declared checked exception.
 *   GzipPacker honors this: it wraps any corruption in IOException (GzipPacker.java:23-29).
 *   LZ4-compressed @IOValue fields are unpacked through IOCompression.Type.LZ4 / LZ4_FAST, which delegate to
 *   Lz4Packer$High / Lz4Packer$Fast (resolved via ServiceLoader, see IOCompression.java:29-46).
 *
 * What it actually does:
 *   Lz4Packer.unpack (jdbfs-lz4/src/main/java/com/lapissea/dfs/io/compress/lz4/Lz4Packer.java:92-99) trusts the
 *   original-length field read from the (unvalidated) header and feeds it straight into
 *   sizeBytes(orgLen) and LZ4FastDecompressor.decompress(packed, off, orgLen):
 *     - a corrupted negative orgLen makes NumberSize.bySize throw IllegalArgumentException
 *       (NumberSize.java:103-105) before decompress even runs, and a negative/huge value that got past that
 *       would make decompress's `new byte[orgLen]` throw NegativeArraySizeException / OOM;
 *     - readSiz (Lz4Packer.java:33-39) even wraps a legitimate IOException in RuntimeException, so the
 *       declared IOException can never escape unpack.
 *   Corrupt LZ4-compressed fields therefore fail with unchecked exceptions and no clean error path.
 *
 * Packed layout (writeSiz, Lz4Packer.java:23-31):
 *   [1 flag byte][1-8 length bytes, little-endian][LZ4 block]
 *   The flag byte encodes the NumberSize of the length field (3 bits + 3 integrity 1-bits, FLAG_MASK=0x38;
 *   INT has ordinal 4 -> 0x3C, LONG has ordinal 7 -> 0x3F).
 *
 * Why the tests fail:
 *   Each test packs valid data, corrupts the leading length header, and asserts — per the declared
 *   Packer.unpack contract — that unpack throws IOException. It instead throws an unchecked exception
 *   (IllegalArgumentException / RuntimeException), so the `fail(...)` in the catch(Throwable) branch fires.
 */
public class ReproLz4CorruptTests{

	// >= 2^24 so NumberSize.bySize picks INT (4-byte little-endian length field), packed[1..4]
	private static final int PAYLOAD = 17_000_000; // 0x0103_6640 -> LE bytes 40 66 03 01

	// NumberSize has 8 constants -> 3 flag bits, integrity bits ((1<<3)-1)<<3 = 0x38
	// INT ordinal 4 -> flag 0x3C, LONG ordinal 7 -> flag 0x3F
	private static final int FLAG_MASK   = 0x38;
	private static final byte FLAG_INT   = (byte)0x3C;
	private static final byte FLAG_LONG  = (byte)0x3F;

	@DataProvider(name = "lz4Types")
	Object[][] lz4Types(){
		// LZ4 -> Lz4Packer$High, LZ4_FAST -> Lz4Packer$Fast (both share Lz4Packer.unpack)
		return new Object[][]{
			{IOCompression.Type.LZ4},
			{IOCompression.Type.LZ4_FAST},
		};
	}

	private static String hex(byte[] data, int n){
		var sb = new StringBuilder();
		for(int i = 0; i<Math.min(n, data.length); i++) sb.append(String.format("%02x ", data[i]));
		return sb.toString().trim();
	}

	private static byte[] packedWithIntLength(IOCompression.Type type) throws IOException{
		byte[] data = new byte[PAYLOAD];
		Arrays.fill(data, (byte)0x42);
		byte[] packed = type.pack(data);

		// sanity: verify the expected header layout (harness check, not the bug under test)
		Assert.assertEquals(packed[0], FLAG_INT, "flag byte should be INT (0x3C), got 0x" + Integer.toHexString(packed[0]&0xFF));
		Assert.assertEquals(packed[0] & FLAG_MASK, FLAG_MASK, "flag byte should carry NumberSize integrity bits, first bytes: " + hex(packed, 8));
		Assert.assertEquals(Arrays.copyOfRange(packed, 1, 5), new byte[]{0x40, 0x66, 0x03, 0x01},
		                   "length field should be 17000000 (0x01036640) little-endian, first bytes: " + hex(packed, 8));
		Assert.assertEquals(type.unpack(packed), data, "uncorrupted round trip must work (harness sanity)");
		return packed;
	}

	@Test(dataProvider = "lz4Types")
	void unpackCorruptLengthSignBit(IOCompression.Type type) throws IOException{
		byte[] packed = packedWithIntLength(type);

		// Corrupt the length header: set the sign bit of the 4-byte little-endian INT length field (MSB = packed[4]).
		// readSiz (Lz4Packer.java:33-39) happily returns it as a negative int (unchecked cast, NumberSize.java:251)
		// and unpack (Lz4Packer.java:92-99) trusts it without any validation.
		byte[] corrupted = packed.clone();
		corrupted[4] |= (byte)0x80;

		try{
			type.unpack(corrupted);
			Assert.fail("unpack of corrupt data must not succeed");
		}catch(IOException expected){
			// the clean error path the declared `throws IOException` (Packer.java:7) promises
		}catch(Throwable t){
			// BUG-44: the corrupted orgLen escapes as an UNCHECKED exception
			// (IllegalArgumentException from NumberSize.bySize, or NegativeArraySizeException/OOM
			//  from decompress's new byte[orgLen]) instead of the declared IOException
			Assert.fail("BUG-44: unpack of corrupted length header threw unchecked " + t.getClass().getName()
				         + " instead of the declared IOException: " + t);
		}
	}

	@Test(dataProvider = "lz4Types")
	void unpackCorruptLengthFlag(IOCompression.Type type) throws IOException{
		byte[] packed = packedWithIntLength(type);

		// Corrupt the flag byte to claim the length field is LONG. NumberSize.readInt then throws
		// IOException("Attempted to read too large of a number") (NumberSize.java:252) and readSiz
		// (Lz4Packer.java:33-39) wraps it in RuntimeException -> unpack can never throw its declared IOException.
		byte[] corrupted = packed.clone();
		corrupted[0] = FLAG_LONG;

		try{
			type.unpack(corrupted);
			Assert.fail("unpack of corrupt data must not succeed");
		}catch(IOException expected){
			// the clean error path the declared `throws IOException` (Packer.java:7) promises
		}catch(Throwable t){
			// BUG-44: readSiz converted the IOException to an unchecked RuntimeException
			Assert.fail("BUG-44: unpack of corrupted length flag threw unchecked " + t.getClass().getName()
				         + " instead of the declared IOException (readSiz wraps IOException in RuntimeException, "
				         + "Lz4Packer.java:33-39): " + t);
		}
	}
}
