package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.bit.EnumUniverse;
import com.lapissea.dfs.io.bit.FlagReader;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class EnumOrdinalEncodingTests{
	public enum Three{ A, B, C }
	public enum Two{ A, B }

	@Test
	void validOrdinalsRoundTrip() throws IOException{
		var universe = EnumUniverse.of(Three.class);
		var data = new byte[8];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(data))){
			for(var value : universe) out.writeEnum(universe, value);
		}
		try(var in = new BitInputStream(new ContentInputStream.BA(data), universe.bitSize*universe.size())){
			for(var value : universe) assertThat(in.readEnum(universe)).isSameAs(value);
		}
	}

	@Test
	void invalidOrdinalThrowsIOException() throws IOException{
		var universe = EnumUniverse.of(Three.class);
		try(var in = new BitInputStream(new ContentInputStream.BA(new byte[]{(byte)0xFF}), universe.bitSize)){
			assertThatThrownBy(() -> in.readEnum(universe)).isInstanceOf(IOException.class)
				.hasMessageContaining("3").hasMessageContaining(Three.class.getName());
		}
	}

	@Test
	void nullableOrdinalsIncludeNull() throws IOException{
		var universe = EnumUniverse.of(Two.class);
		for(int ordinal = 0; ordinal<3; ordinal++){
			try(var in = new BitInputStream(new ContentInputStream.BA(new byte[]{(byte)(0xFC|ordinal)}), universe.nullableBitSize)){
				assertThat(in.readEnum(universe, true)).isSameAs(ordinal == 0? null : universe.get(ordinal - 1));
			}
		}
		try(var in = new BitInputStream(new ContentInputStream.BA(new byte[]{(byte)0xFF}), universe.nullableBitSize)){
			assertThatThrownBy(() -> in.readEnum(universe, true)).isInstanceOf(IOException.class)
				.hasMessageContaining("2").hasMessageContaining(Two.class.getName());
		}
	}

	@Test
	void invalidOrdinalInLaterBatchThrowsIOException() throws IOException{
		var universe = EnumUniverse.of(Three.class);
		var data = new byte[16];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(data))){
			for(int i = 0; i<40; i++) out.writeBits(i == 35? 3 : i%3, universe.bitSize);
		}
		try(var in = new BitInputStream(new ContentInputStream.BA(data), 40*universe.bitSize)){
			assertThatThrownBy(() -> universe.read(40, in)).isInstanceOf(IOException.class)
				.hasMessageContaining("3").hasMessageContaining(Three.class.getName());
		}
	}

	@Test
	void singleFlagRejectsInvalidOrdinal() throws IOException{
		try(var in = new ContentInputStream.BA(new byte[]{(byte)0xFF})){
			assertThatThrownBy(() -> FlagReader.readSingle(in, EnumUniverse.of(Three.class)))
				.isInstanceOf(IOException.class).hasMessageContaining("3").hasMessageContaining(Three.class.getName());
		}
	}
}
