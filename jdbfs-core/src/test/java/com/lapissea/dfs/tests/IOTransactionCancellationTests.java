package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.FileMemoryMappedData;
import com.lapissea.dfs.io.impl.FileRandomAccessData;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOType;
import org.testng.annotations.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class IOTransactionCancellationTests{
	private static MemoryData initial(){ return MemoryData.of(new byte[]{1, 2, 3, 4}); }
	
	@Test
	public void commitPublishesOnlyAtRootClose() throws IOException{
		var raw    = new byte[]{1, 2, 3, 4};
		var writes = new AtomicInteger();
		var data   = MemoryData.builder().withRaw(raw).withOnWrite((source, offsets) -> writes.incrementAndGet()).build();
		try(var root = data.startTransaction()){
			data.write(0, false, new byte[]{9});
			try(var child = data.startTransaction()){
				data.write(1, false, new byte[]{8});
				child.commit();
			}
			assertThat(data.readAll()).containsExactly(9, 8, 3, 4);
			assertThat(writes).hasValue(0);
			root.commit();
			assertThat(writes).hasValue(0);
		}
		assertThat(writes).hasValue(1);
		assertThat(data.readAll()).containsExactly(9, 8, 3, 4);
	}
	
	@Test
	public void missingCommitAndExceptionDiscardAllChanges() throws IOException{
		var data = initial();
		try(var root = data.startTransaction()){
			data.write(0, false, new byte[]{9});
			try(var child = data.startTransaction()){
				data.write(1, false, new byte[]{8});
				child.commit();
			}
		}
		assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
		assertThatThrownBy(() -> data.openIOTransaction(() -> {
			data.write(0, false, new byte[]{7});
			throw new IOException("allocation failed");
		})).isInstanceOf(IOException.class).hasMessage("allocation failed");
		assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
	}
	
	@Test
	public void unsuccessfulChildRejectsEveryWritePathAndRootCommit() throws IOException{
		var data = initial();
		try(var root = data.startTransaction(); var io = data.io()){
			io.write(9);
			try(var child = data.startTransaction()){
				io.write(8);
			}
			assertThatThrownBy(() -> io.write(7)).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.write(new byte[]{7})).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.writeWord(7, 1)).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.writeAtOffsets(List.of(new RandomIO.WriteChunk(0, new byte[]{7})))).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.setCapacity(2)).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.setSize(2)).hasMessage("Transaction is rollback only");
			assertThatThrownBy(() -> io.fillZero(1)).hasMessage("Transaction is rollback only");
			assertThatThrownBy(data::startTransaction).hasMessage("Transaction is rollback only");
			assertThatThrownBy(root::commit).hasMessage("Transaction is rollback only");
		}
		assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
		data.openIOTransaction(() -> data.write(0, false, new byte[]{6}));
		assertThat(data.readAll()).containsExactly(6, 2, 3, 4);
	}
	
	@Test(timeOut = 15_000)
	public void scopesRequireOwnerThreadAndReverseCloseOrder() throws Exception{
		var data = initial();
		var root = data.startTransaction();
		try(var child = data.startTransaction()){
			assertThatThrownBy(root::commit).hasMessageContaining("reverse opening order");
			assertThatThrownBy(root::close).hasMessageContaining("reverse opening order");
			try(var workers = Executors.newSingleThreadExecutor()){
				workers.submit(() -> {
					assertThatThrownBy(child::commit).hasMessageContaining("between threads");
					assertThatThrownBy(child::close).hasMessageContaining("between threads");
				}).get(5, TimeUnit.SECONDS);
			}
			child.commit();
		}finally{
			root.commit();
			root.close();
		}
		root.close();
		assertThatThrownBy(root::commit).hasMessageContaining("closed");
	}
	
	@Test(timeOut = 15_000)
	public void unrelatedWriterWaitsAndSurvivesRollback() throws Exception{
		var data  = initial();
		var ready = new CountDownLatch(1);
		try(var workers = Executors.newSingleThreadExecutor()){
			try(var root = data.startTransaction()){
				data.write(0, false, new byte[]{9});
				var writer = workers.submit(() -> {
					ready.countDown();
					assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
					try(var own = data.startTransaction()){
						data.write(1, false, new byte[]{8});
						own.commit();
					}
					return null;
				});
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> writer.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				root.close();
				writer.get(5, TimeUnit.SECONDS);
			}
		}
		assertThat(data.readAll()).containsExactly(1, 8, 3, 4);
	}
	
	@Test(timeOut = 15_000)
	public void allocatorWaitingOnRootDoesNotHoldItsBookkeepingLock() throws Exception{
		var cluster = Cluster.emptyMem();
		var source  = cluster.getSource();
		var started = new CountDownLatch(1);
		try(var workers = Executors.newSingleThreadExecutor()){
			var root = source.startTransaction();
			try{
				var pending = workers.submit(() -> {
					started.countDown();
					return AllocateTicket.bytes(32).submit(cluster);
				});
				assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> pending.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				var own = AllocateTicket.bytes(32).submit(cluster);
				root.commit();
				root.close();
				var other = pending.get(5, TimeUnit.SECONDS);
				assertThat(other.getPtr()).isNotEqualTo(own.getPtr());
			}finally{
				root.close();
			}
		}
	}
	
	@Test
	public void caughtAllocationFailureStillAbortsItsParent() throws IOException{
		var cluster = Cluster.emptyMem();
		var source  = cluster.getSource();
		var before  = source.readAll();
		try(var root = source.startTransaction()){
			assertThatThrownBy(() -> AllocateTicket.bytes(32).withDataPopulated((provider, io) -> {
				io.writeInt1(9);
				throw new IOException("invalid allocation");
			}).submit(cluster)).isInstanceOf(IOException.class).hasRootCauseMessage("invalid allocation");
			assertThatThrownBy(() -> source.write(0, false, new byte[]{7})).hasMessage("Transaction is rollback only");
			assertThatThrownBy(root::commit).hasMessage("Transaction is rollback only");
		}
		assertThat(source.readAll()).isEqualTo(before);
		assertThat(cluster.getSource()).isSameAs(source);
	}
	
	@Test
	public void truncationAndSparseGrowthAreBuffered() throws IOException{
		var data = initial();
		try(var root = data.startTransaction(); var io = data.io()){
			io.setSize(2);
			assertThat(io.getSize()).isEqualTo(2);
			io.setPos(2);
			assertThat(io.read()).isEqualTo(-1);
			assertThatThrownBy(() -> io.readWord(1)).isInstanceOf(EOFException.class);
		}
		assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
		data.openIOTransaction(() -> {
			try(var io = data.io()){
				io.setPos(7).write(9);
				assertThat(data.readAll()).containsExactly(1, 2, 3, 4, 0, 0, 0, 9);
			}
		});
		assertThat(data.readAll()).containsExactly(1, 2, 3, 4, 0, 0, 0, 9);
		data.openIOTransaction(() -> { try(var io = data.io()){ io.setSize(2); } });
		assertThat(data.readAll()).containsExactly(1, 2);
	}
	
	@Test
	@SuppressWarnings("unchecked")
	public void rollbackRefreshesRetainedCollectionsRootsAndCaches() throws IOException{
		var                       cluster = Cluster.emptyMem();
		var                       source  = cluster.getSource();
		ContiguousIOList<Integer> list    = cluster.roots().builder("list").withType(ContiguousIOList.class, Integer.class).request();
		list.add(11);
		var cachedList = list.cachedView(16, 16);
		assertThat(cachedList.get(0)).isEqualTo(11);
		var iterator = cachedList.iterator();
		var roots    = cluster.roots();
		var types    = cluster.getTypeDb();
		int typeID   = types.toID(IOType.of(Integer.class), false).val();
		types.fromID(typeID);
		var memory = cluster.getMemoryManager();
		for(int i = 0; i<25; i++) roots.require("list", ContiguousIOList.class);
		var cache   = cluster.getChunkCache();
		var chunk   = cluster.getFirstChunk();
		var pointer = chunk.getPtr();
		var before  = source.readAll();
		try(var root = source.startTransaction()){
			list.add(22);
			cachedList.set(0, 33);
			assertThat(list.size()).isEqualTo(2);
		}
		assertThat(source.readAll()).isEqualTo(before);
		assertThat(cluster.getSource()).isSameAs(source);
		assertThat(list.size()).isEqualTo(1);
		assertThat(cachedList.get(0)).isEqualTo(11);
		assertThat(iterator.ioNext()).isEqualTo(11);
		assertThat((Object)roots.require("list", ContiguousIOList.class)).isSameAs(list);
		assertThat(cache.get(pointer)).isSameAs(chunk);
		assertThat(types.fromID(typeID)).isEqualTo(IOType.of(Integer.class));
		assertThat(memory.getFreeChunks()).isNotNull();
		list.add(44);
		assertThat(list.size()).isEqualTo(2);
	}
	
	@Test
	public void blobTransactionIncludesBackingMetadata() throws IOException{
		var cluster = Cluster.emptyMem();
		var source  = cluster.getSource();
		var blob    = Blob.request(cluster, 4);
		var before  = source.readAll();
		try(var root = blob.startTransaction()){
			try(var io = blob.io()){ io.write(new byte[]{1, 2, 3, 4, 5, 6}); }
		}
		assertThat(source.readAll()).isEqualTo(before);
		assertThat(blob.readAll()).isEmpty();
	}
	
	@Test
	public void failedPhysicalCommitInvalidatesProviderAndRejectsFurtherIO(){
		var data = MemoryData.builder().withRaw(new byte[]{1}).withOnWrite((source, offsets) -> {
			throw new IOException("disk failure");
		}).build();
		var provider = DataProvider.newVerySimpleProvider(data);
		assertThatThrownBy(() -> data.openIOTransaction(() -> data.write(0, false, new byte[]{9}))).hasMessageContaining("write event");
		assertThatThrownBy(provider::getSource).hasMessageContaining("invalidated");
		assertThatThrownBy(data::readAll).hasMessageContaining("failed commit");
		assertThatThrownBy(data::startTransaction).hasMessageContaining("failed commit");
	}
	
	@Test
	public void bothFileBackendsPreserveOriginalBytesOnCancellation() throws IOException{
		for(boolean mapped : new boolean[]{false, true}){
			var file = Files.createTempFile("io-rollback-", ".bin");
			try{
				Files.write(file, new byte[]{1, 2, 3, 4});
				try(var data = mapped? new FileMemoryMappedData(file.toFile()) : new FileRandomAccessData(file.toFile())){
					try(var root = data.startTransaction()){
						data.write(0, false, new byte[]{9});
						data.write(20, false, new byte[]{8});
						try(var io = data.io()){ io.setCapacity(2); }
					}
				}
				assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3, 4);
				try(var data = new FileRandomAccessData(file.toFile(), true)){
					assertThat(data.readAll()).containsExactly(1, 2, 3, 4);
				}
			}finally{
				Files.deleteIfExists(file);
			}
		}
	}
}
