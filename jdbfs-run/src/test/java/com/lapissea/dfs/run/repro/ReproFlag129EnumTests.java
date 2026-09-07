package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.exceptions.IllegalBitValue;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.dfs.io.content.ContentWriter;
import com.lapissea.dfs.objects.NumberSize;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;

/**
 * BUG-06 (flag-129-enum) repro: an enum with 129+ constants can never be read back after being
 * written through the single-value flag path — EVERY read throws {@link IllegalBitValue}.
 *
 * What the code is supposed to do:
 *   {@link FlagWriter#writeSingle(ContentWriter, EnumUniverse, Object)} encodes an enum value as
 *   ceil(log2(N)) value bits followed by a run of 1-bits ("integrity bits") filling the rest of
 *   the byte; {@link FlagReader#readSingle(ContentReader, EnumUniverse)} validates those integrity
 *   bits before decoding the value. The single-byte (BYTE) payload can only carry this scheme when
 *   the mask ((1&lt;&lt;eSiz)-1)&lt;&lt;eSiz fits in one byte, i.e. eSiz &lt;= 4 (N &lt;= 16 constants).
 *
 * What it actually does:
 *   For N = 129+ the value field is 8 bits, so in BOTH FlagWriter.writeSingle
 *   (FlagWriter.java:36-39) and FlagReader.readSingle (FlagReader.java:34-39)
 *   integrityBits = ((1&lt;&lt;8)-1)&lt;&lt;8 = 0xFF00 — two full bytes. The writer does
 *   writeInt1(ordinal | 0xFF00) and writeInt1 keeps only the low 8 bits, so the integrity bits are
 *   silently truncated and the stored byte is just the bare ordinal. The reader then evaluates
 *   (data &amp; 0xFF00) != 0xFF00 against a single byte, which is ALWAYS true
 *   (data &amp; 0xFF00 == 0 for any byte), so every read of a 129+-constant enum throws
 *   IllegalBitValue. The live path uses the same code for dynamic enum values:
 *   DynamicSupport.java:76 (write) and DynamicSupport.java:151 (read).
 *   NOTE: the same defect extends to EVERY enum with more than 16 constants — for any eSiz &gt; 4
 *   the mask is wider than the single byte writeInt1 emits (e.g. 128 constants: mask 0x3F80, 17:
 *   mask 0x3E0), so the report's "correct for &lt;=128 constants" boundary is inaccurate; the true
 *   boundary is &lt;=16. roundTrip_128ConstantEnum documents that wider breakage.
 *
 * Why the test fails:
 *   roundTrip_129ConstantEnum writes each of the 129 constants and reads it back; with the bug
 *   every read throws IllegalBitValue, so it fails with "129 of 129 round trips threw
 *   IllegalBitValue on read". roundTrip_128ConstantEnum fails the same way (128 of 128), showing
 *   the defect is not specific to bitSize == 8. roundTrip_16ConstantEnumControl PASSES (mask 0xF0
 *   fits in the byte), proving the harness is sound and isolating the failure to the
 *   too-wide-integrity-mask mechanism.
 */
public class ReproFlag129EnumTests {
	
	public enum Big129{
		C000, C001, C002, C003, C004, C005, C006, C007, C008, C009, C010, C011, C012,
		C013, C014, C015, C016, C017, C018, C019, C020, C021, C022, C023, C024, C025,
		C026, C027, C028, C029, C030, C031, C032, C033, C034, C035, C036, C037, C038,
		C039, C040, C041, C042, C043, C044, C045, C046, C047, C048, C049, C050, C051,
		C052, C053, C054, C055, C056, C057, C058, C059, C060, C061, C062, C063, C064,
		C065, C066, C067, C068, C069, C070, C071, C072, C073, C074, C075, C076, C077,
		C078, C079, C080, C081, C082, C083, C084, C085, C086, C087, C088, C089, C090,
		C091, C092, C093, C094, C095, C096, C097, C098, C099, C100, C101, C102, C103,
		C104, C105, C106, C107, C108, C109, C110, C111, C112, C113, C114, C115, C116,
		C117, C118, C119, C120, C121, C122, C123, C124, C125, C126, C127, C128;
	}	
	public enum Boundary128{
		D000, D001, D002, D003, D004, D005, D006, D007, D008, D009, D010, D011, D012,
		D013, D014, D015, D016, D017, D018, D019, D020, D021, D022, D023, D024, D025,
		D026, D027, D028, D029, D030, D031, D032, D033, D034, D035, D036, D037, D038,
		D039, D040, D041, D042, D043, D044, D045, D046, D047, D048, D049, D050, D051,
		D052, D053, D054, D055, D056, D057, D058, D059, D060, D061, D062, D063, D064,
		D065, D066, D067, D068, D069, D070, D071, D072, D073, D074, D075, D076, D077,
		D078, D079, D080, D081, D082, D083, D084, D085, D086, D087, D088, D089, D090,
		D091, D092, D093, D094, D095, D096, D097, D098, D099, D100, D101, D102, D103,
		D104, D105, D106, D107, D108, D109, D110, D111, D112, D113, D114, D115, D116,
		D117, D118, D119, D120, D121, D122, D123, D124, D125, D126, D127;
	}	
	public enum Tiny16{
		T000, T001, T002, T003, T004, T005, T006, T007, T008, T009, T010, T011, T012,
		T013, T014, T015;
	}	
	@Test
	public void roundTrip_16ConstantEnumControl() throws IOException{
		// Control: 16 constants -> 4 value bits, integrity mask 0xF&lt;&lt;4 = 0xF0 fits in the
		// stored byte, so the identical round trip must succeed. This proves the harness is
		// sound and isolates the failures below to the too-wide-integrity-mask mechanism.
		var universe = EnumUniverse.of(Tiny16.class);
		Assert.assertEquals(universe.bitSize, 4, "premise: 16 constants need 4 value bits");
		
		var failures = countRoundTripFailures(universe);
		Assert.assertEquals(failures, 0,
			"control (16 constants) round trip failed for " + failures + " of " + universe.size() + " constants");
	}
	
	@Test
	public void roundTrip_128ConstantEnum() throws IOException{
		// The bug report claims "&lt;=128 constants" are correct, but 128 constants already use the
		// broken shape: mask ((1&lt;&lt;7)-1)&lt;&lt;7 = 0x3F80 is wider than the 1 byte writeInt1 emits,
		// so it is truncated on write and the reader's check can never pass. Same mechanism as the
		// 129+ case, one size smaller — this documents that the defect is broader than reported.
		var universe = EnumUniverse.of(Boundary128.class);
		Assert.assertEquals(universe.bitSize, 7, "premise: 128 constants need 7 value bits");
		Assert.assertEquals(universe.numSize(false), NumberSize.BYTE, "premise: 7 value bits -> BYTE encoding");
		
		// Writer half of the bug, made visible: writeInt1(ordinal | 0x3F80) keeps only the low
		// 8 bits, so the stored byte is ordinal | (0x3F80 & 0xFF) = ordinal | 0x80.
		var      probe    = Boundary128.values()[0];
		var      probeOut = new ContentOutputBuilder();
		FlagWriter.writeSingle(probeOut, universe, probe);
		Assert.assertEquals(probeOut.toByteArray()[0] & 0xFF, 0x80,
			"integrity mask 0x3F80 truncated by writeInt1 to its low byte 0x80");
		
		// KEY ASSERTION: every just-written constant must read back identically; with the bug the
		// reader's (data & 0x3F80) != 0x3F80 check (FlagReader.java:37) is true for every 1-byte
		// payload (data & 0x3F80 <= 0x0080), so every read throws IllegalBitValue.
		var failures = countRoundTripFailures(universe);
		Assert.assertEquals(failures, 0,
			failures + " of " + universe.size() + " round trips threw IllegalBitValue on read"
			+ " (integrity mask 0x3F80 can never be present in a 1-byte payload)");
	}
	
	@Test
	public void roundTrip_129ConstantEnum() throws IOException{
		var universe = EnumUniverse.of(Big129.class);
		
		// Premise that selects the reported branch: 129 constants -> 8-bit value field, hence a
		// BYTE encoding whose integrity mask is ((1<<8)-1)<<8 = 0xFF00 (two bytes wide).
		Assert.assertEquals(universe.bitSize, 8, "premise: 129 constants need 8 value bits");
		Assert.assertEquals(universe.numSize(false), NumberSize.BYTE, "premise: 8 value bits -> BYTE encoding");
		
		// Writer half of the bug, made visible: writeInt1(ordinal | 0xFF00) (FlagWriter.java:39)
		// truncates the 2-byte integrity mask entirely, leaving the bare ordinal in the byte.
		var      probe    = Big129.values()[64];
		var      probeOut = new ContentOutputBuilder();
		FlagWriter.writeSingle(probeOut, universe, probe);
		byte[]   probeBytes = probeOut.toByteArray();
		Assert.assertEquals(probeBytes.length, 1, "single-value enum must occupy exactly one byte");
		Assert.assertEquals(probeBytes[0] & 0xFF, probe.ordinal(),
			"integrity bits 0xFF00 were truncated by writeInt1 — the stored byte is the bare ordinal");
		
		// KEY ASSERTION: every constant we just wrote must read back identically.
		// With the bug, the reader's (data & 0xFF00) != 0xFF00 check (FlagReader.java:37) is true
		// for every 1-byte payload, so FlagReader.readSingle throws IllegalBitValue for each
		// constant and this assertion fails.
		var failures = countRoundTripFailures(universe);
		Assert.assertEquals(failures, 0,
			failures + " of " + universe.size() + " round trips threw IllegalBitValue on read"
			+ " (integrity mask 0xFF00 can never be present in a 1-byte payload)");
	}
	
	/** Writes every constant of the universe and reads it back; returns the number of failures. */
	private static <T extends Enum<T>> int countRoundTripFailures(EnumUniverse<T> universe) throws IOException{
		int failed = 0;
		T    firstFailure = null;
		for(var value : universe){
			var out = new ContentOutputBuilder();
			FlagWriter.writeSingle(out, universe, value);
			
			var in = new ByteReader(out.toByteArray());
			try{
				var read = FlagReader.readSingle(in, universe);
				if(read != value){
					failed++;
					if(firstFailure == null) firstFailure = value;
				}
			}catch(IllegalBitValue e){
				// reported mechanism: the reader rejects every just-written value
				failed++;
				if(firstFailure == null) firstFailure = value;
			}
		}
		if(failed > 0){
			System.out.println("[repro] first failing constant: " + firstFailure + " (ordinal " + firstFailure.ordinal() + ")");
		}
		return failed;
	}
	
	/** Minimal in-memory {@link ContentReader} over a byte[]. */
	private static final class ByteReader implements ContentReader{
		private final byte[] data;
		private int          pos;
		
		ByteReader(byte[] data){
			this.data = data;
		}
		
		@Override
		public int read() throws IOException{
			return pos < data.length? data[pos++] & 0xFF : -1;
		}
		
		@Override
		public int read(byte[] b, int off, int len) throws IOException{
			if(pos >= data.length) return -1;
			int n = Math.min(len, data.length - pos);
			System.arraycopy(data, pos, b, off, n);
			pos += n;
			return n;
		}
		
		@Override
		public long skip(long toSkip) throws IOException{
			long n = Math.min(toSkip, data.length - pos);
			if(n<0) n = 0;
			pos += (int)n;
			return n;
		}
		
		@Override
		public void close() throws IOException{
		}
	}
}
