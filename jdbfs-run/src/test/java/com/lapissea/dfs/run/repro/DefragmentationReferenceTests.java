package com.lapissea.dfs.run.repro;

import com.lapissea.dfs.run.TestUtils;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.MoveBuffer;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import static com.lapissea.dfs.type.MemoryWalker.CONTINUE;
import static com.lapissea.dfs.type.MemoryWalker.END;
import static com.lapissea.dfs.type.MemoryWalker.SAVE;
import static org.assertj.core.api.Assertions.assertThat;

public class DefragmentationReferenceTests{

	@IOValue
	public static class Payload extends IOInstance.Managed<Payload>{
		@IOValue long value;
		@IOValue ChunkPointer link = ChunkPointer.NULL;

		public Payload(){ }
		public Payload(long value){ this.value = value; }
	}

	public static class Duo extends IOInstance.Managed<Duo>{
		@IOValue @IOValue.Reference @IONullability(IONullability.Mode.NULLABLE) Payload a;
		@IOValue @IOValue.Reference @IONullability(IONullability.Mode.NULLABLE) Payload b;

		@IOValue ChunkPointer raw;

		public Duo(){ }
		public Duo(Payload a, Payload b){
			this.a = a;
			this.b = b;
		}
	}

	@DataProvider
	Object[][] offsets(){ return new Object[][]{{0, 1}, {1, 0}, {1, 2}}; }

	@Test(timeOut = 60_000, dataProvider = "offsets")
	public void sharedReferencesSurviveChunkMove(int first, int second) throws Exception{
		var cluster = Cluster.emptyMem();

		var A = new Payload(111L);
		var B = new Payload(222L);

		long k = 64;

		var C = AllocateTicket.bytes(3 * k + 64).submit(cluster);
		var duo = new Duo();
		duo.raw = C.getPtr();
		cluster.roots().provide("duo", duo);

		C.io(io -> io.setSize(3*k + 64));
		A.link = C.getPtr();
		B.link = C.getPtr();
		var payloadPipe = StandardStructPipe.of(Payload.class);
		Reference.of(C.getPtr(), first*k).write(cluster, false, payloadPipe, A);
		Reference.of(C.getPtr(), second*k).write(cluster, false, payloadPipe, B);

		var         duoStruct = Struct.of(Duo.class);
		@SuppressWarnings("unchecked")
		var refA = (RefField<Duo, Payload>)duoStruct.getFields().requireExact(Payload.class, "a");
		@SuppressWarnings("unchecked")
		var refB = (RefField<Duo, Payload>)duoStruct.getFields().requireExact(Payload.class, "b");
		// Persist two references into one chunk, including their distinct offsets and self-links.
		cluster.rootWalker(new MemoryWalker.PointerRecord(){
			@Override
			public <T extends IOInstance<T>> int log(Reference location, T instance, RefField<T, ?> field, Reference value, Holder holder) throws IOException{
				return CONTINUE;
			}
			@Override
			public <T extends IOInstance<T>> int logChunkPointer(Reference location, T instance, IOField<T, ChunkPointer> field, ChunkPointer value, Holder holder) throws IOException{
				if(!(instance instanceof Duo stored)) return CONTINUE;
				stored.raw = C.getPtr();
				stored.a = A;
				stored.b = B;
				refA.setReference(stored, Reference.of(C.getPtr(), first*k));
				refB.setReference(stored, Reference.of(C.getPtr(), second*k));
				return SAVE|END;
			}
		}, false).walk();
		var before = TestUtils.reopenMem(cluster).roots().request("duo", Duo.class);
		assertThat(refA.getReference(before)).isEqualTo(Reference.of(C.getPtr(), first*k));
		assertThat(refB.getReference(before)).isEqualTo(Reference.of(C.getPtr(), second*k));

		assertThat(Reference.of(C.getPtr(), first*k).readNew(cluster, payloadPipe, null).value)
		               .as("first payload before the move").isEqualTo(111L);
		assertThat(Reference.of(C.getPtr(), second*k).readNew(cluster, payloadPipe, null).value)
		               .as("second payload before the move").isEqualTo(222L);

		MoveBuffer move = DefragmentManager.moveReference(cluster, C.getPtr(), t -> t);
		assertThat(move.hasAny()).as("chunk C must actually be moved by moveReference").isTrue();
		var newC = move.toDest(C.getPtr()).orElseThrow();

		assertThat(newC).as("C must have been copied to a new location and the old one freed").isNotEqualTo(C.getPtr());

		duo = cluster.roots().request("duo", Duo.class);
		assertThat(duo.raw).isEqualTo(newC);
		assertThat(refA.getReference(duo)).isEqualTo(Reference.of(newC, first*k));
		var bRef = refB.getReference(duo);
		assertThat(bRef).as("b's reference must not be null after the move").isNotNull();
		assertThat(bRef.getPtr()).as("b's reference must point at the new chunk, not the freed one").isEqualTo(newC);
		assertThat(bRef.getOffset()).as("second payload offset").isEqualTo(second*k);

		// Reuse and overwrite the original allocation so stale references cannot read valid payloads.
		var D = AllocateTicket.bytes(C.getCapacity()).submit(cluster);
		assertThat(D.getPtr()).as("the freed chunk C is reused by the allocator").isEqualTo(C.getPtr());

		D.io(io -> io.setSize(3*k));
		var sentinel = new Payload(999L);
		Reference.of(D.getPtr(), first*k).write(cluster, false, payloadPipe, sentinel);
		Reference.of(D.getPtr(), second*k).write(cluster, false, payloadPipe, sentinel);

		assertThat(bRef.readNew(cluster, payloadPipe, null).value)
		               .as("b should still be the original payload 222, not the sentinel in reused memory")
		               .isEqualTo(222L);
		var reopened = TestUtils.reopenMem(cluster);
		var restored = reopened.roots().request("duo", Duo.class);
		assertThat(restored.a.value).isEqualTo(111L);
		assertThat(restored.b.value).isEqualTo(222L);
		assertThat(restored.raw).isEqualTo(newC);
		assertThat(restored.a.link).isEqualTo(newC);
		assertThat(restored.b.link).isEqualTo(newC);
		assertThat(refA.getReference(restored)).isEqualTo(Reference.of(newC, first*k));
		assertThat(refB.getReference(restored)).isEqualTo(Reference.of(newC, second*k));

	}
}
