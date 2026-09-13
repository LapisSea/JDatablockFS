package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class InlineSealedEncodingTests{
	sealed interface Seal{
		final class A extends IOInstance.Managed<A> implements Seal{
			@IOValue int a;
			public A(){ }
			public A(int a){ this.a = a; }
		}

		// Different payload sizes require skip to resolve the subtype.
		final class B extends IOInstance.Managed<B> implements Seal{
			@IOValue int x;
			@IOValue int y;
			public B(){ }
			public B(int x, int y){ this.x = x; this.y = y; }
		}
	}

	static final class Holder extends IOInstance.Managed<Holder>{
		@IOValue Seal seal;
		public Holder(){ }
		public Holder(Seal seal){ this.seal = seal; }
	}

	@org.testng.annotations.DataProvider
	public Object[][] values(){
		return new Object[][]{{new Seal.A(42)}, {new Seal.B(7, 9)}};
	}

	private static byte[] write(StandardStructPipe<Holder> pipe, DataProvider provider, Seal value) throws IOException{
		var out = new ContentOutputBuilder();
		pipe.write(provider, out, new Holder(value));
		return out.toByteArray();
	}

	@Test(dataProvider = "values")
	public void registeredSubtypeReadsAndSkipsExactly(Seal value) throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var pipe = StandardStructPipe.of(Holder.class);
		var encoded = write(pipe, provider, value);
		try(var input = new ContentInputStream.BA(encoded)){
			assertThat(pipe.readNew(provider, input, null)).isEqualTo(new Holder(value));
			assertThat(input.read()).isEqualTo(-1);
		}
		try(var input = new ContentInputStream.BA(encoded)){
			pipe.skip(provider, input, null);
			assertThat(input.read()).isEqualTo(-1);
		}
	}

	@Test(dataProvider = "values")
	public void unknownSubtypeReportsIdOnReadAndSkip(Seal value) throws IOException{
		var provider = DataProvider.newVerySimpleProvider();
		var pipe = StandardStructPipe.of(Holder.class);
		var encoded = write(pipe, provider, value);
		int unknown = 99;
		assertThat(provider.getTypeDb().fromID(Seal.class, unknown)).isNull();
		// The first byte carries the ID width; the second carries this one-byte ID.
		assertThat(encoded.length).isGreaterThan(2);
		int known = Byte.toUnsignedInt(encoded[1]);
		assertThat(provider.getTypeDb().fromID(Seal.class, known)).isEqualTo(value.getClass());
		encoded[1] = (byte)unknown;
		try(var input = new ContentInputStream.BA(encoded)){
			assertThatThrownBy(() -> pipe.readNew(provider, input, null))
				.isInstanceOf(MalformedStruct.class).hasMessageContaining("99").hasMessageContaining("Seal");
		}
		try(var input = new ContentInputStream.BA(encoded)){
			assertThatThrownBy(() -> pipe.skip(provider, input, null))
				.isInstanceOf(MalformedStruct.class).hasMessageContaining("99").hasMessageContaining("Seal");
		}
	}
}

