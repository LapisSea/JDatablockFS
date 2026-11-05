package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.io.instancepipe.FixedVaryingStructPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.collections.ContiguousIOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class NestedSpecializedPipeTests{
	
	@IOValue
	@StructPipe.Special
	public static class Child extends IOInstance.Managed<Child>{
		@IODependency.VirtualNumSize
		private long value;
		
		public Child()          { }
		public Child(long value){ this.value = value; }
	}
	
	@IOValue
	public static class CachedChild extends IOInstance.Managed<CachedChild>{
		@IODependency.VirtualNumSize
		private long value;
		
		public CachedChild()          { }
		public CachedChild(long value){ this.value = value; }
	}
	
	@IOValue
	@StructPipe.Special
	public static class IntChild extends IOInstance.Managed<IntChild>{
		@IODependency.VirtualNumSize
		private int value;
		
		public IntChild()         { }
		public IntChild(int value){ this.value = value; }
	}
	
	@Test
	public void generatedLongReadersSupportEverySignedStorageWidth() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				for(var size : NumberSize.values()){
					var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(Child.class), VaryingSize.Provider.repeat(List.of(size)));
					assertGenerated(pipe);
					assertThat(pipe.getSizeDescriptor().requireFixed(WordSpace.BYTE)).as("%s storage size", size).isEqualTo(size.bytes);
					long[] values = size == NumberSize.VOID? new long[]{0} : new long[]{0, -1, size.signedMinValue, size.signedMaxValue};
					for(long value : values){
						pipe.write(chunk, new Child(value));
						assertThat(pipe.readNew(chunk, null).value).as("%s read of %s", size, value).isEqualTo(value);
					}
				}
			}
		}
	}
	
	@Test
	public void generatedIntReadersSupportEverySignedStorageWidth() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				for(var size : NumberSize.values()){
					if(size.bytes>Integer.BYTES) continue;
					var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(IntChild.class), VaryingSize.Provider.repeat(List.of(size)));
					assertGenerated(pipe);
					assertThat(pipe.getSizeDescriptor().requireFixed(WordSpace.BYTE)).as("%s storage size", size).isEqualTo(size.bytes);
					int[] values = size == NumberSize.VOID? new int[]{0} : new int[]{0, -1, (int)size.signedMinValue, size.signedMaxValueI};
					for(int value : values){
						pipe.write(chunk, new IntChild(value));
						assertThat(pipe.readNew(chunk, null).value).as("%s read of %s", size, value).isEqualTo(value);
					}
				}
			}
		}
	}
	
	@Test
	public void cachedNestedPipePreservesParentSizeId() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.NEVER)){
				var struct = Struct.of(CachedChild.class);
				// Learn the child layout before constructing the same child at the parent's second size slot.
				FixedVaryingStructPipe.tryVarying(struct, VaryingSize.Provider.repeat(List.of(NumberSize.BYTE)));
				var provider = VaryingSize.Provider.repeat(List.of(NumberSize.BYTE, NumberSize.BYTE));
				provider.provide(NumberSize.LONG, null, false);
				var pipe           = FixedVaryingStructPipe.tryVarying(struct, provider);
				var cachedProvider = VaryingSize.Provider.repeat(List.of(NumberSize.BYTE, NumberSize.BYTE));
				cachedProvider.provide(NumberSize.LONG, null, false);
				assertThat(FixedVaryingStructPipe.tryVarying(struct, cachedProvider)).isSameAs(pipe);
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				assertThatThrownBy(() -> pipe.write(chunk, new CachedChild(300)))
					.isInstanceOfSatisfying(VaryingSize.TooSmall.class, error -> {
						assertThat(error.tooSmallIdMap).containsOnlyKeys(new VaryingSize(NumberSize.BYTE, 1));
						assertThat(error.tooSmallIdMap.get(new VaryingSize(NumberSize.BYTE, 1))).isEqualTo(NumberSize.SHORT);
					});
			}
		}
	}
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"left", "right", "tail"})
	public static class Element extends IOInstance.Managed<Element>{
		private Child left;
		private Child right;
		private byte  tail;
		
		public Element(){ }
		public Element(long left, long right, byte tail){
			this.left = new Child(left);
			this.right = new Child(right);
			this.tail = tail;
		}
	}
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"left", "right"})
	public static class ImmutableElement extends IOInstance.Managed<ImmutableElement>{
		private final Child left;
		private final Child right;
		
		public ImmutableElement(Child left, Child right){
			this.left = left;
			this.right = right;
		}
	}
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"child", "tail"})
	public static class NullableElement extends IOInstance.Managed<NullableElement>{
		@IONullability(IONullability.Mode.NULLABLE)
		private Child child;
		private byte  tail;
		
		public NullableElement(){ }
		public NullableElement(Child child, byte tail){
			this.child = child;
			this.tail = tail;
		}
	}
	
	@IOValue
	@StructPipe.Special
	public static class TemporalElement extends IOInstance.Managed<TemporalElement>{
		private Duration      duration;
		private Instant       instant;
		private LocalDateTime localDateTime;
		
		public TemporalElement(){ }
	}
	
	@Test
	public void varyingReadersPreserveTemporalWrapperLayouts() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(TemporalElement.class),
				                                             VaryingSize.Provider.constLimit(NumberSize.INT, 0));
				assertGenerated(pipe);
				var value = new TemporalElement();
				value.duration = Duration.ofSeconds(300, 123_000_000);
				value.instant = Instant.ofEpochSecond(1_000_000_000, 234_000_000);
				value.localDateTime = LocalDateTime.of(2020, 3, 4, 0, 0, 1, 345_000_000);
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				pipe.write(chunk, value);
				var result = pipe.readNew(chunk, null);
				assertThat(result.duration).isEqualTo(value.duration);
				assertThat(result.instant).isEqualTo(value.instant);
				assertThat(result.localDateTime).isEqualTo(value.localDateTime);
			}
		}
	}
	
	@Test
	public void varyingNestedLayoutPreservesImmutableConstruction() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(ImmutableElement.class),
				                                             VaryingSize.Provider.repeat(List.of(NumberSize.BYTE, NumberSize.LONG)));
				assertGenerated(pipe);
				assertThat(pipe.getType().needsBuilderObj()).isTrue();
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				pipe.write(chunk, new ImmutableElement(new Child(12), new Child(5_000_000_000L)));
				var result = pipe.readNew(chunk, null);
				assertThat(result.left.value).isEqualTo(12);
				assertThat(result.right.value).isEqualTo(5_000_000_000L);
				var fromBuilder = pipe.getBuilderPipe().readNew(chunk, null).build();
				assertThat(fromBuilder.left.value).isEqualTo(12);
				assertThat(fromBuilder.right.value).isEqualTo(5_000_000_000L);
			}
		}
	}
	
	@Test
	public void varyingNullableChildKeepsTrailingFieldAligned() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(NullableElement.class),
				                                             VaryingSize.Provider.repeat(List.of(NumberSize.SHORT, NumberSize.BYTE)));
				assertGenerated(pipe);
				var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
				for(var value : List.of(new NullableElement(new Child(300), (byte)91), new NullableElement(null, (byte)92))){
					pipe.write(chunk, value);
					var result = pipe.readNew(chunk, null);
					if(value.child == null) assertThat(result.child).isNull();
					else assertThat(result.child.value).isEqualTo(value.child.value);
					assertThat(result.tail).isEqualTo(value.tail);
				}
			}
		}
	}
	
	@Test
	public void generatedReadersUseStandardAndFixedNestedPipes() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var value = new Element(12, 5_000_000_000L, (byte)91);
				assertRoundTrip(StandardStructPipe.of(Element.class, StagedInit.STATE_DONE), value);
				assertRoundTrip(FixedStructPipe.of(Element.class, StagedInit.STATE_DONE), value);
			}
		}
	}
	
	@Test
	public void generatedReaderPreservesDistinctNestedLayouts() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				// Both fields contain Child, but their configured pipes have different widths.
				var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(Element.class),
				                                             VaryingSize.Provider.repeat(List.of(NumberSize.BYTE, NumberSize.LONG, NumberSize.BYTE)));
				assertThat(pipe.getSizeDescriptor().requireFixed(WordSpace.BYTE)).isEqualTo(10);
				assertRoundTrip(pipe, new Element(12, 5_000_000_000L, (byte)91));
			}
		}
	}
	
	@Test
	public void contiguousListReadsNestedObjectsAcrossWidthChanges() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var                       cluster = Cluster.emptyMem();
				ContiguousIOList<Element> list    = cluster.roots().request("nested", ContiguousIOList.class, Element.class);
				var values = List.of(
					new Element(1, 2, (byte)10),
					new Element(300, 3, (byte)11),
					new Element(4, 70_000, (byte)12),
					new Element(5_000_000_000L, 5, (byte)13)
				);
				for(var value : values){
					list.add(value);
					assertGenerated(list.<Element>getFieldPipe(null, null));
					for(int i = 0; i<list.size(); i++) assertElement(list.get(i), values.get(i));
				}
				var replacement = new Element(8, 9_000_000_000L, (byte)14);
				list.set(1, replacement);
				assertElement(list.get(1), replacement);
				assertElement(list.get(0), values.getFirst());
				assertElement(list.get(3), values.getLast());
				ContiguousIOList<Element> reopened = TestUtils.reopenMem(cluster).roots()
				                                              .request("nested", ContiguousIOList.class, Element.class);
				assertGenerated(reopened.<Element>getFieldPipe(null, null));
				assertThat(reopened.size()).isEqualTo(values.size());
				for(int i = 0; i<reopened.size(); i++){
					assertElement(reopened.get(i), i == 1? replacement : values.get(i));
				}
			}
		}
	}
	
	private static void assertRoundTrip(StructPipe<Element> pipe, Element value) throws IOException{
		assertGenerated(pipe);
		var chunk = AllocateTicket.bytes(64).submit(DataProvider.newVerySimpleProvider());
		pipe.write(chunk, value);
		assertElement(pipe.readNew(chunk, null), value);
		try(var io = chunk.io()){
			var into = new Element();
			pipe.read(chunk.getDataProvider(), io, into, null);
			assertElement(into, value);
		}
	}
	
	private static void assertGenerated(StructPipe<?> pipe){
		if(pipe instanceof CheckedPipe.Standard<?> checked) pipe = checked.getUncheckedPipe();
		if(pipe instanceof CheckedPipe.Fixed<?> checked) pipe = checked.getUncheckedPipe();
		assertThat(pipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
	}
	
	private static void assertElement(Element actual, Element expected){
		assertThat(actual.left.value).isEqualTo(expected.left.value);
		assertThat(actual.right.value).isEqualTo(expected.right.value);
		assertThat(actual.tail).isEqualTo(expected.tail);
	}
}
