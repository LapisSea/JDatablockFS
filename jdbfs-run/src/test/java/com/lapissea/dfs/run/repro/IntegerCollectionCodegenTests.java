package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.lapissea.dfs.type.StagedInit.STATE_DONE;
import static org.assertj.core.api.Assertions.assertThat;

public class IntegerCollectionCodegenTests{
	@IOValue
	@StructPipe.Special
	public static class Value extends IOInstance.Managed<Value>{
		@IONullability(IONullability.Mode.NULLABLE)
		public Integer[]          array;
		@IONullability(IONullability.Mode.NULLABLE)
		public int[]              primitive;
		@IONullability(IONullability.Mode.NULLABLE)
		public List<Integer>      list;
		public ArrayList<Integer> concrete;
		public int                tail;
	}
	
	@StructPipe.Special
	@IOInstance.Order({"array", "list", "tail"})
	public interface ConstructorValue extends IOInstance.Def<ConstructorValue>{
		@IONullability(IONullability.Mode.NULLABLE)
		Integer[] array();
		@IONullability(IONullability.Mode.NULLABLE)
		List<Integer> list();
		int tail();
	}
	
	@Test
	public void readConstructorCollections() throws IOException{
		var pipe = StandardStructPipe.of(ConstructorValue.class, STATE_DONE);
		assertThat(pipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
		var chunk = AllocateTicket.bytes(32).submit(DataProvider.newVerySimpleProvider());
		for(var values : List.of(List.<Integer>of(), List.of(0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE))){
			for(boolean nulls : new boolean[]{false, true}){
				var value = IOInstance.Def.of(ConstructorValue.class,
				                              nulls? null : values.toArray(Integer[]::new),
				                              nulls? null : new ArrayList<>(values), 12345678);
				pipe.write(chunk, value);
				var read = pipe.readNew(chunk, null);
				assertThat(read.array()).isEqualTo(value.array());
				assertThat(read.list()).isEqualTo(value.list());
				assertThat(read.tail()).isEqualTo(value.tail());
			}
		}
	}
	
	@Test
	public void readCollections() throws IOException{
		var type      = Struct.of(Value.class, STATE_DONE);
		var basic     = new StandardStructPipe<>(type, STATE_DONE);
		var generated = StandardStructPipe.of(Value.class, STATE_DONE);
		assertThat(generated).isInstanceOf(StructPipe.SpecializedImplementation.class);
		var chunk = AllocateTicket.bytes(32).submit(DataProvider.newVerySimpleProvider());
		for(var values : List.of(List.<Integer>of(), List.of(0, 0, 0), List.of(-128, 127),
		                         List.of(-32768, 32767), List.of(-8388608, 8388607),
		                         List.of(Integer.MIN_VALUE, Integer.MAX_VALUE))){
			for(boolean nulls : new boolean[]{false, true}){
				var value = new Value();
				value.array = nulls? null : values.toArray(Integer[]::new);
				value.primitive = nulls? null : values.stream().mapToInt(Integer::intValue).toArray();
				value.list = nulls? null : new ArrayList<>(values);
				value.concrete = new ArrayList<>(values);
				value.tail = 0x12345678;
				basic.write(chunk, value);
				assertValue(generated.readNew(chunk, null), value);
				var reused = new Value();
				try(var io = chunk.io()){
					generated.read(chunk.getDataProvider(), io, reused, null);
				}
				assertValue(reused, value);
			}
		}
	}
	
	private static void assertValue(Value actual, Value expected){
		assertThat(actual.array).isEqualTo(expected.array);
		assertThat(actual.primitive).isEqualTo(expected.primitive);
		assertThat(actual.list).isEqualTo(expected.list);
		assertThat(actual.concrete).isEqualTo(expected.concrete).isInstanceOf(ArrayList.class);
		assertThat(actual.tail).isEqualTo(expected.tail);
	}
}
