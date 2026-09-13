package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.SizeDescriptor;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StructPipeAllocationLimitTests{
	static final long OVERSIZED = (long)Integer.MAX_VALUE + 1;

	// A synthetic descriptor exercises the buffer limit without allocating a multi-gigabyte object.
	static final class OversizedPipe extends StandardStructPipe<Blob>{
		OversizedPipe(Struct<Blob> struct){
			super(struct, StructPipe.STATE_DONE);
		}
		@Override
		protected SizeDescriptor<Blob> createSizeDescriptor(){
			return SizeDescriptor.Fixed.of(WordSpace.BYTE, OVERSIZED);
		}
	}

	@Test
	public void directWriteRejectsAllocationAboveBufferLimit() throws IOException{
		var pipe = new OversizedPipe(Struct.of(Blob.class, StructPipe.STATE_DONE));
		assertThat(pipe.getSizeDescriptor().calcAllocSize(WordSpace.BYTE)).isEqualTo(OVERSIZED);
		var provider = DataProvider.newVerySimpleProvider();
		try(var identity = AllocateTicket.bytes(128).submitAsTempMem(provider);
		    var destination = AllocateTicket.bytes(128).submitAsTempMem(provider)){
			var blob = new Blob(provider, identity.chunk(), IOType.of(Blob.class));
			try(var io = destination.chunk().io()){
				assertThat(io.isDirect()).isTrue();
				assertThatThrownBy(() -> pipe.write(provider, io, blob))
					.isInstanceOf(IOException.class)
					.hasMessageContaining(Long.toString(OVERSIZED))
					.hasMessageContaining("buffer limit");
			}
		}
	}
}

