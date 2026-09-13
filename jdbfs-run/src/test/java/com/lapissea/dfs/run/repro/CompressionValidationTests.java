package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.compress.RlePacker;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.bit.FlagWriter;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Packed data must round-trip, and malformed headers and blocks must report IOException. */
public class CompressionValidationTests{
	@DataProvider
	public Object[][] compressionTypes(){
		return new Object[][]{{IOCompression.Type.RLE}, {IOCompression.Type.LZ4}, {IOCompression.Type.LZ4_FAST}};
	}

	private static byte[] twoRuns(){
		var data = new byte[200];
		Arrays.fill(data, 0, 100, (byte)1);
		Arrays.fill(data, 100, 200, (byte)2);
		return data;
	}

	@Test(dataProvider = "compressionTypes")
	public void validPayloadsRoundTrip(IOCompression.Type type) throws IOException{
		for(byte[] data : new byte[][]{new byte[0], {42}, {1, 2, 3, 4, 5}, twoRuns()}){
			assertThat(type.unpack(type.pack(data))).as("%s round trip of %d bytes", type, data.length).isEqualTo(data);
		}
	}

	@DataProvider
	public Object[][] rleCorruptions(){
		return new Object[][]{
			{"invalid size ordinal", 0, 8},
			{"invalid block length width", 2, 0x20},
			{"declared total too large", 1, 255},
			{"declared total too small", 1, 199},
			{"first run too large", 3, 255},
			{"second run too large", 6, 255}
		};
	}

	@Test(dataProvider = "rleCorruptions")
	public void rleRejectsMalformedLengths(String scenario, int offset, int replacement){
		var packer = new RlePacker();
		var packed = packer.pack(twoRuns());
		// One total-length header followed by two explicit 100-byte repeat blocks.
		assertThat(packed).containsExactly(1, (byte)200, 4, 100, 1, 4, 100, 2);
		packed[offset] = (byte)replacement;
		assertThatThrownBy(() -> packer.unpack(packed)).as(scenario).isInstanceOf(IOException.class);
	}

	@Test
	public void rleRejectsNonemptyTruncatedPrefixes(){
		var packer = new RlePacker();
		var packed = packer.pack(twoRuns());
		// An empty buffer is itself a valid encoding of empty data.
		for(int length = 1; length<packed.length; length++){
			var truncated = Arrays.copyOf(packed, length);
			assertThatThrownBy(() -> packer.unpack(truncated))
				.as("packed prefix of %d bytes", length).isInstanceOf(IOException.class);
		}
	}

	@DataProvider
	public Object[][] lz4Types(){
		return new Object[][]{{IOCompression.Type.LZ4}, {IOCompression.Type.LZ4_FAST}};
	}

	@Test(dataProvider = "lz4Types")
	public void lz4RejectsNegativeLength(IOCompression.Type type) throws IOException{
		var out = new ContentOutputBuilder();
		FlagWriter.writeSingle(out, NumberSize.FLAG_INFO, NumberSize.INT);
		out.writeInt4(Integer.MIN_VALUE);
		assertThatThrownBy(() -> type.unpack(out.toByteArray())).isInstanceOf(IOException.class);
	}

	@Test(dataProvider = "lz4Types")
	public void lz4RejectsLengthWiderThanInt(IOCompression.Type type) throws IOException{
		var out = new ContentOutputBuilder();
		FlagWriter.writeSingle(out, NumberSize.FLAG_INFO, NumberSize.LONG);
		out.writeInt8(1L<<32);
		assertThatThrownBy(() -> type.unpack(out.toByteArray())).isInstanceOf(IOException.class);
	}

	@Test(dataProvider = "lz4Types")
	public void lz4RejectsTruncatedHeaderAndBlock(IOCompression.Type type) throws IOException{
		var packed = type.pack(twoRuns());
		for(int length : new int[]{0, 1, packed.length - 1}){
			var truncated = Arrays.copyOf(packed, length);
			assertThatThrownBy(() -> type.unpack(truncated))
				.as("%s packed prefix of %d bytes", type, length).isInstanceOf(IOException.class);
		}
	}

	@Test(dataProvider = "lz4Types")
	public void lz4RejectsInvalidFlagPadding(IOCompression.Type type) throws IOException{
		var packed = type.pack(twoRuns());
		packed[0] &= 0x7F;
		assertThatThrownBy(() -> type.unpack(packed)).isInstanceOf(IOException.class);
	}
}
