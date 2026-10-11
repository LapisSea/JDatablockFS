package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.assertj.core.api.Assertions.assertThat;

public class NullableReferenceWalkTests{

	@IOValue
	public static class Payload extends IOInstance.Managed<Payload>{
		@IOValue long value;
		
		public Payload()          { }
		public Payload(long value){ this.value = value; }
	}
	
	public static class Duo extends IOInstance.Managed<Duo>{
		@IOValue @IOValue.Reference @IONullability(IONullability.Mode.NULLABLE) Payload a;
		@IOValue @IOValue.Reference @IONullability(IONullability.Mode.NULLABLE) Payload b;
		
		public Duo(){ }
	}
	
	public static class ChunkPtrHolder extends IOInstance.Managed<ChunkPtrHolder>{
		@IOValue
		private ChunkPointer ptr;
		
		@IOValue @IOValue.Reference @IONullability(IONullability.Mode.NULLABLE) Payload b;
		
		public ChunkPtrHolder(){ }
	}
	
	@DataProvider
	public Object[][] nullableFields(){
		return new Object[][]{{false, false}, {true, false}, {false, true}, {true, true}};
	}
	
	@Test(dataProvider = "nullableFields")
	public void walkNullableReferenceFields(boolean firstPresent, boolean secondPresent) throws Exception{
		var cluster = Cluster.emptyMem();
		
		var duo = new Duo();
		duo.a = firstPresent? new Payload(11) : null;
		duo.b = secondPresent? new Payload(22) : null;
		var pipe = StandardStructPipe.of(Duo.class);
		try(var allocation = AllocateTicket.bytes(128).submitAsTempMem(cluster)){
			try(var io = allocation.chunk().io()){
				pipe.write(cluster, io, duo);
			}
			var walker = new MemoryWalker(cluster, duo, allocation.chunk().getPtr().makeReference(),
			                              pipe, false, MemoryWalker.PointerRecord.NOOP);
			assertThat(walker.walk()).isEqualTo(MemoryWalker.CONTINUE);
		}
	}
	
	@Test
	public void walkNullableChunkPointerThenRefSucceeds() throws Exception{
		var cluster = Cluster.emptyMem();
		
		var holder = new ChunkPtrHolder();
		holder.ptr = ChunkPointer.NULL;
		cluster.roots().provide("holder", holder);
		
		var walker = cluster.rootWalker(MemoryWalker.PointerRecord.NOOP, false);
		
		assertThat(walker.walk()).isEqualTo(MemoryWalker.CONTINUE);
	}
}
