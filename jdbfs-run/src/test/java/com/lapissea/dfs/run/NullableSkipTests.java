package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class NullableSkipTests{
	public static class Value extends IOInstance.Managed<Value>{
		@IOValue @IONullability(IONullability.Mode.NULLABLE)
		public Double value;
		@IOValue public boolean flag;
	}
	
	@Test
	public void skipNullableBoxedValue() throws IOException{
		var pipe = new StandardStructPipe<>(Struct.of(Value.class), Struct.STATE_DONE);
		var provider = DataProvider.newVerySimpleProvider();
		for(Double number : new Double[]{null, 0D, 123456789D}){
			var value = new Value();
			value.value = number;
			var chunk = AllocateTicket.bytes(32).submit(provider);
			try(var io = chunk.io()){
				pipe.write(provider, io, value);
				io.writeInt8(0x123456789ABCDEFL);
				io.setPos(0);
				pipe.skip(provider, io, null);
				assertThat(io.readInt8()).isEqualTo(0x123456789ABCDEFL);
			}
		}
	}
}
