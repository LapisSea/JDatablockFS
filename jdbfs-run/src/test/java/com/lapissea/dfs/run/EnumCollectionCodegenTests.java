package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
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

import static org.assertj.core.api.Assertions.assertThat;

public class EnumCollectionCodegenTests{
	public enum Choice{ A, B, C, D, E }
	
	@StructPipe.Special
	public static class Collections extends IOInstance.Managed<Collections>{
		@IOValue @IONullability(IONullability.Mode.NULLABLE)
		public List<Choice> list;
		@IOValue @IONullability(IONullability.Mode.NULLABLE)
		public ArrayList<Choice> arrayList;
		@IOValue @IONullability(IONullability.Mode.NULLABLE)
		public Choice[] array;
		@IOValue public int trailing;
	}
	
	@Test
	public void roundTrip() throws IOException{
		StructPipe<Collections> pipe = StandardStructPipe.of(Collections.class, StructPipe.STATE_DONE);
		var unchecked = pipe instanceof CheckedPipe.Standard<Collections> checked? checked.getUncheckedPipe() : pipe;
		assertThat(unchecked).isInstanceOf(StructPipe.SpecializedImplementation.class);
		for(int count : new int[]{-1, 0, 1, 7, 23, 128}){
			var value = new Collections();
			value.trailing = 123456;
			if(count>=0){
				value.list = new ArrayList<>();
				for(int i = 0; i<count; i++) value.list.add(Choice.values()[i%Choice.values().length]);
				value.arrayList = new ArrayList<>(value.list);
				value.array = value.list.toArray(Choice[]::new);
			}
			var chunk = AllocateTicket.bytes(10).submit(DataProvider.newVerySimpleProvider());
			pipe.write(chunk, value);
			check(pipe.readNew(chunk, null), value);
			var existing = new Collections();
			existing.list = List.of(Choice.E);
			existing.arrayList = new ArrayList<>(existing.list);
			existing.array = new Choice[]{Choice.E};
			try(var io = chunk.io()){
				pipe.read(chunk.getDataProvider(), io, existing, null);
			}
			check(existing, value);
		}
	}
	
	@StructPipe.Special
	@IOInstance.Order({"list", "array", "trailing"})
	public interface ConstructorCollections extends IOInstance.Def<ConstructorCollections>{
		@IONullability(IONullability.Mode.NULLABLE) List<Choice> list();
		@IONullability(IONullability.Mode.NULLABLE) Choice[] array();
		int trailing();
	}

	@Test
	public void readConstructorCollections() throws IOException{
		var pipe = StandardStructPipe.of(ConstructorCollections.class, StructPipe.STATE_DONE);
		var unchecked = pipe instanceof CheckedPipe.Standard<ConstructorCollections> checked? checked.getUncheckedPipe() : pipe;
		assertThat(unchecked).isInstanceOf(StructPipe.SpecializedImplementation.class);
		var interpreted = new StandardStructPipe<>(Struct.of(ConstructorCollections.class), StructPipe.STATE_DONE);
		var chunk = AllocateTicket.bytes(16).submit(DataProvider.newVerySimpleProvider());
		for(int count : new int[]{-1, 0, 1, 21, 22, 128}){
			var values = new ArrayList<Choice>();
			for(int j = 0; j<count; j++) values.add(Choice.values()[j%Choice.values().length]);
			var value = IOInstance.Def.of(ConstructorCollections.class,
				count<0? null : values, count<0? null : values.toArray(Choice[]::new), 0x12345678);
			interpreted.write(chunk, value);
			var result = pipe.readNew(chunk, null);
			assertThat(result.list()).isEqualTo(value.list());
			assertThat(result.array()).isEqualTo(value.array());
			assertThat(result.trailing()).isEqualTo(value.trailing());
		}
	}

	private static void check(Collections actual, Collections expected){
		assertThat(actual.list).isEqualTo(expected.list);
		assertThat(actual.arrayList).isEqualTo(expected.arrayList);
		assertThat(actual.array).isEqualTo(expected.array);
		assertThat(actual.trailing).isEqualTo(expected.trailing);
	}
}
