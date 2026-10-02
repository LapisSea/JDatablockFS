package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IOCompression;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

public class FieldVariationCodegenTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	public enum Choice{ A, B, C }
	
	@StructPipe.Special
	public static class Value extends IOInstance.Managed<Value>{
		@IOValue @IONullability(IONullability.Mode.NULLABLE) public Choice choice;
		@IOValue @IOCompression(IOCompression.Type.RLE) public byte[] rle;
		@IOValue @IOCompression(IOCompression.Type.GZIP) public byte[] gzip;
		@IOValue @IOCompression(IOCompression.Type.RLE) public ByteBuffer writable;
		@IOValue @IOCompression(IOCompression.Type.GZIP) public ByteBuffer readOnly;
		@IOValue public int trailing;
	}
	
	@Test
	public void nullableEnumAndCompression() throws IOException{
		StructPipe<Value> generated = StandardStructPipe.of(Value.class, StructPipe.STATE_DONE);
		var unchecked = generated instanceof CheckedPipe.Standard<Value> checked? checked.getUncheckedPipe() : generated;
		assertThat(unchecked).isInstanceOf(StructPipe.SpecializedImplementation.class);
		var interpreted = new StandardStructPipe<>(Struct.of(Value.class), StructPipe.STATE_DONE);
		for(Choice choice : new Choice[]{null, Choice.A, Choice.B, Choice.C}){
			for(int length : new int[]{0, 1, 37, 257}){
				var value = new Value();
				value.choice = choice;
				value.rle = new byte[length];
				for(int i = 0; i<length; i++) value.rle[i] = (byte)(i/5);
				value.gzip = value.rle.clone();
				value.writable = ByteBuffer.wrap(value.rle.clone());
				value.readOnly = ByteBuffer.wrap(value.rle.clone()).asReadOnlyBuffer();
				value.trailing = 0x12345678;
				var chunk = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
				interpreted.write(chunk, value);
				check(generated.readNew(chunk, null), value);
				var existing = new Value();
				existing.choice = Choice.C;
				try(var io = chunk.io()){
					generated.read(chunk.getDataProvider(), io, existing, null);
				}
				check(existing, value);
			}
		}
	}
	
	private static void check(Value actual, Value expected){
		assertThat(actual.choice).isEqualTo(expected.choice);
		assertThat(actual.rle).isEqualTo(expected.rle);
		assertThat(actual.gzip).isEqualTo(expected.gzip);
		assertThat(actual.writable).isEqualTo(expected.writable);
		assertThat(actual.writable.isReadOnly()).isFalse();
		assertThat(actual.readOnly).isEqualTo(expected.readOnly);
		assertThat(actual.readOnly.isReadOnly()).isTrue();
		assertThat(actual.trailing).isEqualTo(expected.trailing);
	}
}
