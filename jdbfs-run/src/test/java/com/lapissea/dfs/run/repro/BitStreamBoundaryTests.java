package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.io.bit.BitInputStream;
import com.lapissea.dfs.io.bit.BitOutputStream;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputStream;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class BitStreamBoundaryTests{

	@Test
	void unalignedFullWordPreservesFollowingBits() throws IOException{
		var buff = new byte[24];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
			out.writeBits(-1L, 64); // word1: bits   0..63  all 1
			out.writeBits(-1L, 64); // bits 64..127
			out.writeBits(0L, 64);  // bits 128..191
			out.requireWritten(192);
		}

		try(var in = new BitInputStream(new ContentInputStream.BA(buff), 0)){
			assertThat(in.readBits(63)).as("read #1: 63 bits").isEqualTo((1L << 63) - 1);
			assertThat(in.readBits(64)).as("read #2: 64 bits").isEqualTo(-1L);
			assertThat(in.readBits(63)).as("read #3: 63 bits right after the 64-bit read").isEqualTo(1L);
			assertThat(in.readBits(2)).as("read #4: 2 trailing bits").isEqualTo(0L);
		}
	}

	@Test
	void readBits64Exact() throws IOException{
		var buff = new byte[8];
		try(var out = new BitOutputStream(new ContentOutputStream.BA(buff))){
			out.writeBits(0xDEADBEEFCAFEBABEL, 64);
			out.requireWritten(64);
		}
		try(var in = new BitInputStream(new ContentInputStream.BA(buff), 0)){
			assertThat(in.readBits(64)).as("64-bit read").isEqualTo(0xDEADBEEFCAFEBABEL);
		}
	}
}
