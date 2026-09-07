package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * BUG-07 enum-evolution — enums serialize by ordinal, so universe drift between
 * writer and reader is silently mis-mapped, and corrupt ordinals blow up raw.
 *
 * What the code is supposed to do:
 * Enum constants are serialized as compact ordinal bit-fields:
 * {@link EnumUniverse#write} (jdbfs-core .../io/bit/EnumUniverse.java:92-94) stores
 * {@code source.ordinal()} in {@code bitSize} bits, and {@link EnumUniverse#read}
 * (EnumUniverse.java:81-83) maps the read bits back to a constant via {@code get(index)}
 * so a stream stays decodable across writer/reader pairs.
 *
 * What it actually does:
 * (a) There is no name-based validation between the writer's and the reader's
 *     universe. {@code get(int)} (EnumUniverse.java:155-157) is a bare
 *     {@code universe[index]} array lookup, so bytes written by universe v1 {A,B,C}
 *     and read through universe v2 {A,C,D} map ordinal 2 (writer's C) to D — a silent
 *     wrong-constant result with no error at all.
 * (b) A corrupt ordinal that is valid for the bit width but >= the universe size
 *     (e.g. 3 in a 2-bit/3-constant universe) falls into the same bare array lookup
 *     and throws a raw {@link java.lang.ArrayIndexOutOfBoundsException} instead of a
 *     clean descriptive error (contrast: malformed bit values elsewhere surface as
 *     {@code IllegalBitValue}, an IOException, e.g. in FlagReader).
 *
 * Why the tests fail:
 * (a) writes V1.C (ordinal 2) with universe v1, re-reads the identical bytes with
 *     universe v2 and asserts the constant name is "C" — the reader returns "D".
 * (b) feeds an out-of-range ordinal (bits "11" = 3) and asserts the read surfaces a
 *     clean IOException per the BitReader contract — the raw AIOOBE is thrown instead.
 */
public class ReproEnumEvolutionTests{
	
	/** "Writer side" universe: version 1 of the enum (3 constants, 2-bit encoding). */
	public enum V1{ A, B, C }
	/** "Reader side" universe: evolved version — B removed, D added (ordinals shifted). */
	public enum V2{ A, C, D }
	
	@Test
	void crossUniverseReadSilentlyMapsToWrongConstant() throws IOException{
		var v1 = EnumUniverse.of(V1.class);
		var v2 = EnumUniverse.of(V2.class);
		// both universes must use the same 2-bit encoding so the cross-read is byte-compatible
		assertThat(v1.bitSize).as("writer and reader universes share the 2-bit encoding").isEqualTo(v2.bitSize);
		
		var buff = new byte[8];
		// write V1.C (ordinal 2) with universe v1 -> bits "10"
		try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
			out.writeEnum(v1, V1.C);
		}
		
		// sanity: same-universe round trip works (proves the harness is correct)
		try(var in = new BitInputStream(new ContentInputStream.BA(buff), v1.bitSize)){
			assertThat(in.readEnum(v1)).as("same-universe round trip").isSameAs(V1.C);
		}
		
		// re-read the SAME bytes with the evolved universe v2 {A,C,D}
		try(var in = new BitInputStream(new ContentInputStream.BA(buff), v2.bitSize)){
			var read = in.readEnum(v2);
			// BUG: ordinal 2 is interpreted positionally against v2, so the reader
			// returns D — a different constant than the C the writer stored. Silent.
			assertThat(read.name())
				.as("cross-universe read must not silently map ordinal 2 (writer's C) to another constant")
				.isEqualTo("C");
		}
	}
	
	@Test
	void corruptOrdinalYieldsRawAioobeInsteadOfCleanError() throws IOException{
		var v2 = EnumUniverse.of(V2.class);
		// 3 constants in 2 bits: ordinals 0..2 are valid, 3 (bits "11") is out of range
		assertThat(v2.bitSize).isEqualTo(2);
		assertThat(v2.size()).isEqualTo(3);
		
		var buff = new byte[8];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
			// craft a corrupt stream: write ordinal 3 directly, per the ordinal encoding
			out.writeBits(3, v2.bitSize);
		}
		
		try(var in = new BitInputStream(new ContentInputStream.BA(buff), v2.bitSize)){
			var thrown = catchThrowable(() -> in.readEnum(v2));
			// BUG: EnumUniverse.get (EnumUniverse.java:155-157) is a bare universe[index]
			// array lookup, so the out-of-range ordinal 3 surfaces as a raw
			// ArrayIndexOutOfBoundsException instead of a clean descriptive IOException.
			assertThat(thrown)
				.as("out-of-range ordinal must surface a clean descriptive error (IOException), not a raw AIOOBE")
				.isInstanceOf(IOException.class);
		}
	}
	
}
