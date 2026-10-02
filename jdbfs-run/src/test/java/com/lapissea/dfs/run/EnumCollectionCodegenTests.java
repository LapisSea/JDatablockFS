package com.lapissea.dfs.run;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class EnumCollectionCodegenTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }
	
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
	
	private static void check(Collections actual, Collections expected){
		assertThat(actual.list).isEqualTo(expected.list);
		assertThat(actual.arrayList).isEqualTo(expected.arrayList);
		assertThat(actual.array).isEqualTo(expected.array);
		assertThat(actual.trailing).isEqualTo(expected.trailing);
	}
}
