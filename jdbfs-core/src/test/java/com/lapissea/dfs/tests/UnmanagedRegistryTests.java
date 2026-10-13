package com.lapissea.dfs.tests;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.UnmanagedRegistry;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.IOTransactionState;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOHashSet;
import com.lapissea.dfs.objects.collections.IOTreeSet;
import com.lapissea.dfs.objects.collections.LinkedIOList;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.Struct;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

public class UnmanagedRegistryTests{
	@Test
	public void factoryAndRoutedProviderShareIdentity() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var blob     = Blob.request(provider, 32);
		var struct   = (Struct.Unmanaged<Blob>)Struct.of(Blob.class);
		assertThat(struct.make(provider, blob.getPointer().dereference(provider), blob.getTypeDef())).isSameAs(blob);
		var routed = provider.withRouter(ticket -> ticket);
		assertThat(struct.make(routed, blob.getPointer().dereference(routed), blob.getTypeDef())).isSameAs(blob);
		assertThatThrownBy(() -> new Blob(provider, blob.getPointer().dereference(provider), blob.getTypeDef())).hasMessageContaining("already exists");
	}
	@Test(timeOut = 15_000)
	public void concurrentLoadsHaveOneIdentity() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var chunk    = AllocateTicket.bytes(32).submit(provider);
		try(var pool = Executors.newFixedThreadPool(8)){
			var tasks = new ArrayList<Future<Blob>>();
			for(int i = 0; i<100; i++)
				tasks.add(pool.submit(() -> ((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, chunk, IOType.of(Blob.class))));
			var canonical = tasks.getFirst().get(5, TimeUnit.SECONDS);
			for(var task : tasks) assertThat(task.get(5, TimeUnit.SECONDS)).isSameAs(canonical);
			assertThat(provider.getUnmanagedRegistry().size()).isEqualTo(1);
		}
	}
	@Test
	public void rollbackRefreshesRetainedListAndAllowsFurtherWrites() throws Exception{
		var                       source  = MemoryData.empty();
		var                       cluster = Cluster.init(source);
		ContiguousIOList<Integer> list    = cluster.roots().builder("values").withType(ContiguousIOList.class, Integer.class).request();
		list.addAll(List.of(1, 2, 3));
		var before = source.readAll();
		try(var transaction = source.startTransaction()){
			list.addAll(List.of(4, 5));
			list.set(0, 9);
		}
		assertThat(source.readAll()).isEqualTo(before);
		assertThat(list.size()).isEqualTo(3);
		assertThat(list.get(0)).isEqualTo(1);
		assertThat((Object)cluster.roots().require("values", ContiguousIOList.class)).isSameAs(list);
		list.add(6);
		assertThat(list.get(3)).isEqualTo(6);
	}
	@Test
	public void rollbackRefreshesLinkedNodesMapsAndSetCaches() throws Exception{
		var                         cluster = Cluster.emptyMem();
		LinkedIOList<Integer>       linked  = cluster.roots().builder("linked").withType(LinkedIOList.class, Integer.class).request();
		HashIOMap<Integer, Integer> map     = cluster.roots().builder("map").withType(HashIOMap.class, Integer.class, Integer.class).request();
		IOHashSet<Integer>          hash    = cluster.roots().builder("hash").withType(IOHashSet.class, Integer.class).request();
		IOTreeSet<Integer>          tree    = cluster.roots().builder("tree").withType(IOTreeSet.class, Integer.class).request();
		linked.addAll(List.of(1, 2, 3));
		map.put(1, 10);
		for(var value : List.of(1, 2, 3)){ hash.add(value); tree.add(value); }
		var before = cluster.getSource().readAll();
		try(var tx = cluster.getSource().startTransaction()){
			linked.set(0, 9);
			linked.remove(1);
			linked.add(4);
			map.put(1, 99);
			map.put(4, 40);
			hash.remove(2); hash.add(4);
			tree.remove(2); tree.add(4);
			assertThat(tree.contains(4)).isTrue();
		}
		assertThat(cluster.getSource().readAll()).isEqualTo(before);
		assertThat(linked.size()).isEqualTo(3);
		for(int i = 0; i<3; i++) assertThat(linked.get(i)).isEqualTo(i + 1);
		assertThat(map.get(1)).isEqualTo(10);
		assertThat(map.size()).isEqualTo(1);
		assertThat(hash.contains(2)).isTrue(); assertThat(hash.contains(4)).isFalse();
		assertThat(tree.contains(2)).isTrue(); assertThat(tree.contains(4)).isFalse();
		linked.add(5); map.put(5, 50); hash.add(5); tree.add(5);
		assertThat(linked.get(3)).isEqualTo(5);
		assertThat(map.get(5)).isEqualTo(50);
		assertThat(hash.contains(5)).isTrue(); assertThat(tree.contains(5)).isTrue();
	}
	@Test
	public void rolledBackAllocationLosesIdentity() throws Exception{
		var  provider = DataProvider.newVerySimpleProvider();
		Blob blob;
		try(var transaction = provider.getSource().startTransaction()){
			blob = Blob.request(provider, 32);
			blob.write(0, false, new byte[]{1});
		}
		assertThatThrownBy(blob::getPointer).hasMessageContaining("no longer has");
		assertThatThrownBy(blob::io).hasMessageContaining("no longer has");
		assertThat(provider.getUnmanagedRegistry().size()).isZero();
		Blob.request(provider, 32).write(0, false, new byte[]{2});
	}
	@Test
	public void refusingToFreeAnOpenChunkDoesNotFailTheParentTransaction() throws Exception{
		var cluster = Cluster.emptyMem();
		var blob    = Blob.request(cluster, 32);
		try(var tx = cluster.getSource().startTransaction()){
			try(var io = blob.io()){
				assertThatThrownBy(() -> cluster.getMemoryManager().free(blob.getPointer().dereference(cluster)))
					.isInstanceOf(FreeWhileUsed.class);
			}
			blob.write(0, false, new byte[]{7});
			tx.commit();
		}
		assertThat(blob.readAll()).containsExactly(7);
	}
	@Test
	public void rollbackRestoresFreedIdentity() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var blob     = Blob.request(provider, 32);
		blob.write(0, false, new byte[]{1, 2, 3});
		var pointer = blob.getPointer();
		try(var transaction = provider.getSource().startTransaction()){
			blob.free();
			assertThatThrownBy(blob::io).hasMessageContaining("no longer has");
		}
		assertThat(blob.getPointer()).isEqualTo(pointer);
		assertThat(blob.readAll()).containsExactly(1, 2, 3);
		assertThat(((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, pointer.dereference(provider), blob.getTypeDef())).isSameAs(blob);
	}
	@Test
	public void relocationKeepsAliasesCanonicalAndRollsBack() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var blob     = Blob.request(provider, 16);
		blob.write(0, false, new byte[]{1, 2, 3});
		var original = blob.getPointer();
		var before   = provider.getSource().readAll();
		try(var tx = provider.getSource().startTransaction()){
			var moved = AllocateTicket.bytes(16).withDataPopulated((p, io) -> io.write(blob.readAll())).submit(provider);
			blob.notifyReferenceMovement(moved);
			assertThat(((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, original.dereference(provider), blob.getTypeDef())).isSameAs(blob);
			assertThat(((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, moved, blob.getTypeDef())).isSameAs(blob);
			provider.getMemoryManager().free(original.dereference(provider));
			assertThat(blob.getPointer()).isEqualTo(moved.getPtr());
		}
		assertThat(provider.getSource().readAll()).isEqualTo(before);
		assertThat(blob.getPointer()).isEqualTo(original);
		assertThat(blob.readAll()).containsExactly(1, 2, 3);
		assertThat(provider.getUnmanagedRegistry().size()).isEqualTo(1);
	}
	@Test
	public void freeThenReuseAndRollbackRestoreTheOriginalInstance() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var original = Blob.request(provider, 32);
		original.write(0, false, new byte[]{7});
		var  pointer = original.getPointer();
		Blob replacement;
		try(var tx = provider.getSource().startTransaction()){
			original.free();
			replacement = Blob.request(provider, 32);
			replacement.write(0, false, new byte[]{9});
		}
		assertThat(original.getPointer()).isEqualTo(pointer);
		assertThat(original.readAll()).containsExactly(7);
		assertThatThrownBy(replacement::io).hasMessageContaining("no longer has");
		assertThat(provider.getMemoryManager().getFreeChunks().size()).isZero();
	}
	
	@IOValue
	public static class Value extends IOInstance.Managed<Value>{
		public int number;
		public Value()          { }
		public Value(int number){ this.number = number; }
	}
	@Test
	public void managedSnapshotsRemainDetachedButCachesAreCleared() throws Exception{
		var                     cluster = Cluster.emptyMem();
		ContiguousIOList<Value> list    = cluster.roots().builder("snapshots").withType(ContiguousIOList.class, Value.class).request();
		list.add(new Value(1));
		var cached   = list.cachedView(16, 16);
		var snapshot = cached.get(0);
		try(var tx = cluster.getSource().startTransaction()){
			snapshot.number = 9;
			cached.set(0, snapshot);
		}
		assertThat(snapshot.number).isEqualTo(9);
		assertThat(cached.get(0).number).isEqualTo(1);
		assertThat(cached.get(0)).isNotSameAs(snapshot);
	}
	@Test
	public void aRolledBackListMayReportItsCachedSizeButCannotDoIO() throws Exception{
		var                       cluster = Cluster.emptyMem();
		ContiguousIOList<Integer> list;
		try(var tx = cluster.getSource().startTransaction()){
			list = cluster.roots().builder("new-list").withType(ContiguousIOList.class, Integer.class).request();
			list.add(42);
		}
		assertThat(list.size()).isEqualTo(1);
		assertThatThrownBy(() -> list.get(0)).hasMessageContaining("no longer has");
	}
	@Test
	public void incompatibleGenericTypesAreRejected() throws Exception{
		var                       cluster = Cluster.emptyMem();
		ContiguousIOList<Integer> list    = cluster.roots().builder("typed").withType(ContiguousIOList.class, Integer.class).request();
		assertThatThrownBy(() -> list.getThisStruct().make(cluster, list.getPointer().dereference(cluster), IOType.of(ContiguousIOList.class, String.class)))
			.hasMessageContaining("Conflicting unmanaged types");
	}
	@Test
	public void anObjectLoadedDuringAFailedRootKeepsItsExistingIdentity() throws Exception{
		var  provider = DataProvider.newVerySimpleProvider();
		var  chunk    = AllocateTicket.bytes(16).submit(provider);
		Blob loaded;
		try(var tx = provider.getSource().startTransaction()){
			loaded = ((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, chunk, IOType.of(Blob.class));
			loaded.write(0, false, new byte[]{9});
		}
		assertThat(loaded.getPointer()).isEqualTo(chunk.getPtr());
		assertThat(loaded.readAll()).isEmpty();
		loaded.write(0, false, new byte[]{7});
		assertThat(loaded.readAll()).containsExactly(7);
	}
	
	@Test
	public void readOnlyViewsCannotExposeAWritableCanonicalInstance() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var blob     = Blob.request(provider, 32);
		var source   = provider.getSource();
		var view = new IOInterface(){
			@Override
			public boolean isReadOnly()                    { return true; }
			@Override
			public IOTransaction startTransaction()        { return IOTransaction.NOOP; }
			@Override
			public RandomIO io() throws IOException        { return source.readOnlyIO(); }
			@Override
			public IOTransactionState.Access lockIO()      { return source.lockIO(); }
			@Override
			public UnmanagedRegistry getUnmanagedRegistry(){ return source.getUnmanagedRegistry(); }
		};
		var reader = DataProvider.newVerySimpleProvider(view);
		assertThat(reader.getUnmanagedRegistry()).isSameAs(provider.getUnmanagedRegistry());
		assertThatThrownBy(() -> ((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(reader, blob.getPointer().dereference(reader), blob.getTypeDef()))
			.hasMessageContaining("cannot mix read-only");
	}
	
	@Test
	public void blobDatabasesHaveIndependentPointerNamespacesAndShareRollback() throws Exception{
		var                       outer = Cluster.emptyMem();
		var                       blob  = Blob.request(outer, 8192);
		var                       inner = Cluster.init(blob);
		ContiguousIOList<Integer> list  = inner.roots().builder("inner-values").withType(ContiguousIOList.class, Integer.class).request();
		list.add(1);
		var before = outer.getSource().readAll();
		try(var tx = outer.getSource().startTransaction()){
			list.set(0, 2);
		}
		assertThat(outer.getSource().readAll()).isEqualTo(before);
		assertThat(list.size()).isEqualTo(1);
		assertThat(list.get(0)).isEqualTo(1);
		assertThat(blob.getUnmanagedRegistry()).isNotSameAs(outer.getUnmanagedRegistry());
		list.set(0, 3);
		assertThat(list.get(0)).isEqualTo(3);
	}
	@Test
	public void rolledBackBlobDatabaseDetachesItsContents() throws Exception{
		var                       outer = Cluster.emptyMem();
		ContiguousIOList<Integer> list;
		try(var tx = outer.getSource().startTransaction()){
			var inner = Cluster.init(Blob.request(outer, 8192));
			list = inner.roots().builder("inner").withType(ContiguousIOList.class, Integer.class).request();
			list.add(1);
		}
		assertThatThrownBy(() -> list.get(0)).hasMessageContaining("no longer has");
		assertThat(outer.getSource().readAll()).isNotEmpty();
	}
	
	@Test
	public void databaseCreatedInAnExistingEmptyBlobIsDetachedOnRollback() throws Exception{
		var                       outer = Cluster.emptyMem();
		var                       blob  = Blob.request(outer, 8192);
		ContiguousIOList<Integer> list;
		try(var tx = outer.getSource().startTransaction()){
			var inner = Cluster.init(blob);
			list = inner.roots().builder("inner").withType(ContiguousIOList.class, Integer.class).request();
			list.add(1);
		}
		assertThat(blob.readAll()).isEmpty();
		assertThatThrownBy(() -> list.get(0)).hasMessageContaining("no longer has");
		blob.write(0, false, new byte[]{(byte)255});
		try(var io = blob.io()){ assertThat(io.read()).isEqualTo(255); assertThat(io.read()).isEqualTo(-1); }
	}
	
	@IOValue
	public static class ColdChild extends IOInstance.Managed<ColdChild>{
		public int number;
		public ColdChild(){ }
	}
	
	@IOValue
	public static class ColdParent extends IOInstance.Managed<ColdParent>{
		public ColdChild child;
		public ColdParent(){ }
	}
	@Test(timeOut = 15_000)
	public void coldDependentSchemasCanBeValidatedWhileTheRootOwnsTheIOGate() throws Exception{
		var cluster = Cluster.emptyMem();
		var parent  = new ColdParent();
		parent.child = new ColdChild();
		parent.child.number = 5;
		try(var tx = cluster.getSource().startTransaction()){
			cluster.roots().provide("cold", parent);
			tx.commit();
		}
		assertThat(cluster.roots().require("cold", ColdParent.class).child.number).isEqualTo(5);
	}
	
	private static WeakReference<Cluster> ephemeralCluster(MemoryData source) throws Exception{
		return new WeakReference<>(Cluster.init(source));
	}
	@Test(timeOut = 20_000)
	public void registryDoesNotIndirectlyRetainAProviderThroughItsChunks() throws Exception{
		var source   = MemoryData.empty();
		var provider = ephemeralCluster(source);
		assertThat(source.getUnmanagedRegistry().size()).isPositive();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while(provider.get() != null && System.nanoTime()<deadline){ System.gc(); Thread.sleep(20); }
		assertThat(provider.get()).isNull();
		assertThat(source.getUnmanagedRegistry().size()).isZero();
	}
	
	@Test(timeOut = 20_000)
	public void registryReleasesCollectedInstances() throws Exception{
		var provider = DataProvider.newVerySimpleProvider();
		var retained = Blob.request(provider, 1);
		var refs     = new ArrayList<WeakReference<Blob>>();
		var strong   = new ArrayList<Blob>();
		for(int i = 0; i<1000; i++) strong.add(Blob.request(provider, 1));
		for(var blob : strong) refs.add(new WeakReference<>(blob));
		var initial = provider.getUnmanagedRegistry().size();
		assertThat(initial).isEqualTo(1001);
		strong.clear();
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while(refs.stream().anyMatch(ref -> ref.get() != null) && System.nanoTime()<deadline){
			System.gc();
			Thread.sleep(20);
		}
		assertThat(refs).allMatch(ref -> ref.get() == null);
		assertThat(provider.getUnmanagedRegistry().size()).isLessThan(initial).isEqualTo(1);
		assertThat(((Struct.Unmanaged<Blob>)Struct.of(Blob.class)).make(provider, retained.getPointer().dereference(provider), retained.getTypeDef())).isSameAs(retained);
	}
}
