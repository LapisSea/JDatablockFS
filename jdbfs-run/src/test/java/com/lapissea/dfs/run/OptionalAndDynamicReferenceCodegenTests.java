package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
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
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

public class OptionalAndDynamicReferenceCodegenTests{
	static{ IOInstance.allowFullAccessI(MethodHandles.lookup()); }

	@IOValue
	@StructPipe.Special
	public static class Child extends IOInstance.Managed<Child>{
		private long value;
		public Child(){ }
		public Child(long value){ this.value = value; }
	}

	@IOValue
	@StructPipe.Special
	public static class Optionals extends IOInstance.Managed<Optionals>{
		private Optional<Integer> number = Optional.empty();
		private Optional<Boolean> flag = Optional.empty();
		private Optional<Child> child = Optional.empty();
		private byte tail;
		public Optionals(){ }
	}

	@StructPipe.Special
	@IOInstance.Order({"number", "child", "tail"})
	public interface ConstructorOptionals extends IOInstance.Def<ConstructorOptionals>{
		Optional<Integer> number();
		Optional<Child> child();
		byte tail();
	}

	@IOValue
	@StructPipe.Special
	public static class UnmanagedReferences extends IOInstance.Managed<UnmanagedReferences>{
		private ContiguousIOList<Integer> numbers;
		private ContiguousIOList<Child> children;
		@IONullability(IONullability.Mode.NULLABLE)
		private ContiguousIOList<Integer> nullable;
		private byte tail;
		public UnmanagedReferences(){ }
	}

	@IOValue
	@StructPipe.Special
	public static class DynamicReference extends IOInstance.Managed<DynamicReference>{
		@IOValue.Generic
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Object value;
		private byte tail;
		public DynamicReference(){ }
	}

	@IOValue
	@StructPipe.Special
	public static class RequiredDynamicReference extends IOInstance.Managed<RequiredDynamicReference>{
		@IOValue.Generic
		@IOValue.Reference
		private Object value;
		private byte tail;
		public RequiredDynamicReference(){ }
	}

	@Test
	public void constructorOptionalReadersUseCompanionLocals() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var struct = Struct.of(ConstructorOptionals.class, StagedInit.STATE_DONE);
				var basic = new StandardStructPipe<>(struct, StagedInit.STATE_DONE);
				var generated = StandardStructPipe.of(ConstructorOptionals.class, StagedInit.STATE_DONE);
				assertGenerated(generated);
				var provider = DataProvider.newVerySimpleProvider();
				var source = AllocateTicket.bytes(1024).submit(provider);
				var rewritten = AllocateTicket.bytes(1024).submit(provider);
				for(boolean present : new boolean[]{false, true}){
					var number = present? Optional.of(-300) : Optional.<Integer>empty();
					var child = present? Optional.of(new Child(5_000_000_000L)) : Optional.<Child>empty();
					var value = IOInstance.Def.of(ConstructorOptionals.class, number, child, (byte)95);
					basic.write(source, value);
					var read = generated.readNew(source, null);
					assertThat(read.number()).isEqualTo(number);
					assertThat(read.child()).isEqualTo(child);
					assertThat(read.tail()).isEqualTo((byte)95);
					generated.write(rewritten, read);
					var reread = basic.readNew(rewritten, null);
					assertThat(reread.number()).isEqualTo(number);
					assertThat(reread.child()).isEqualTo(child);
					assertThat(reread.tail()).isEqualTo((byte)95);
				}
			}
		}
	}

	@Test
	public void requiredDynamicReferenceReadersPreserveValues() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var struct = Struct.of(RequiredDynamicReference.class, StagedInit.STATE_DONE);
				var basic = new StandardStructPipe<>(struct, StagedInit.STATE_DONE);
				var generated = StandardStructPipe.of(RequiredDynamicReference.class, StagedInit.STATE_DONE);
				assertGenerated(generated);
				var provider = DataProvider.newVerySimpleProvider();
				for(Object item : new Object[]{-300, new Child(5_000_000_000L)}){
					var value = new RequiredDynamicReference();
					value.value = item;
					value.tail = 96;
					crossRead(basic, generated, provider, value, RequiredDynamicReference::new, actual -> {
						assertThat(actual.value).isEqualTo(item);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}

	@Test
	public void optionalReadersPreserveEmptyAndPresentValues() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var struct = Struct.of(Optionals.class, StagedInit.STATE_DONE);
				var basic = new StandardStructPipe<>(struct, StagedInit.STATE_DONE);
				var generated = StandardStructPipe.of(Optionals.class, StagedInit.STATE_DONE);
				assertGenerated(generated);
				var provider = DataProvider.newVerySimpleProvider();
				for(int state = 0; state<3; state++){
					var value = new Optionals();
					value.number = state == 0? Optional.empty() : Optional.of(state == 1? -300 : Integer.MAX_VALUE);
					value.flag = state == 0? Optional.empty() : Optional.of(state == 1);
					value.child = state == 0? Optional.empty() : Optional.of(new Child(5_000_000_000L));
					value.tail = (byte)(90 + state);
					crossRead(basic, generated, provider, value, Optionals::new, actual -> {
						assertThat(actual.number).isEqualTo(value.number);
						assertThat(actual.flag).isEqualTo(value.flag);
						assertThat(actual.child).isEqualTo(value.child);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}

	@Test
	public void unmanagedReadersPreserveGenericListsAndConfiguredPointerWidths() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var cluster = Cluster.emptyMem();
				ContiguousIOList<Integer> numbers = cluster.roots().request("numbers", ContiguousIOList.class, Integer.class);
				ContiguousIOList<Child> children = cluster.roots().request("children", ContiguousIOList.class, Child.class);
				numbers.addAll(List.of(-300, 0, 70_000));
				children.addAll(List.of(new Child(-300), new Child(5_000_000_000L)));
				var struct = Struct.of(UnmanagedReferences.class, StagedInit.STATE_DONE);
				var varying = FixedVaryingStructPipe.tryVarying(struct, VaryingSize.Provider.constLimit(NumberSize.SHORT, 0));
				var fixed = FixedStructPipe.of(UnmanagedReferences.class, StagedInit.STATE_DONE);
				assertThat(varying.getSizeDescriptor().requireFixed(WordSpace.BYTE))
					.isLessThan(fixed.getSizeDescriptor().requireFixed(WordSpace.BYTE));
				for(var generated : List.<StructPipe<UnmanagedReferences>>of(
					StandardStructPipe.of(UnmanagedReferences.class, StagedInit.STATE_DONE), fixed, varying)){
					assertGenerated(generated);
					StructPipe<UnmanagedReferences> basic = generated == varying?
						new FixedVaryingStructPipe<>((FixedVaryingStructPipe<UnmanagedReferences>)varying, StagedInit.STATE_DONE) :
						generated == fixed? new FixedStructPipe<>(struct, StagedInit.STATE_DONE) :
						new StandardStructPipe<>(struct, StagedInit.STATE_DONE);
					for(boolean nullList : new boolean[]{false, true}){
						var value = new UnmanagedReferences();
						value.numbers = numbers;
						value.children = children;
						value.nullable = nullList? null : numbers;
						value.tail = 93;
						crossRead(basic, generated, cluster, value, UnmanagedReferences::new, actual -> {
							assertList(actual.numbers, numbers);
							assertList(actual.children, children);
							if(nullList) assertThat(actual.nullable).isNull();
							else assertList(actual.nullable, numbers);
							assertThat(actual.tail).isEqualTo(value.tail);
						});
					}
				}
			}
		}
	}

	@Test
	public void dynamicReferenceReadersPreservePolymorphicValuesAndReferenceMetadata() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var struct = Struct.of(DynamicReference.class, StagedInit.STATE_DONE);
				var basic = new StandardStructPipe<>(struct, StagedInit.STATE_DONE);
				var generated = StandardStructPipe.of(DynamicReference.class, StagedInit.STATE_DONE);
				assertGenerated(generated);
				var cluster = Cluster.emptyMem();
				ContiguousIOList<Integer> list = cluster.roots().request("dynamic", ContiguousIOList.class, Integer.class);
				list.addAll(List.of(1, -300, 70_000));
				for(Object item : new Object[]{null, 70_000, -5_000_000_000L, "dynamic reference", new Child(-300),
					new ArrayList<>(List.of(1, -300, 70_000)), new ArrayList<>(List.of(1L, -5_000_000_000L, 70_000L)), list}){
					var value = new DynamicReference();
					value.value = item;
					value.tail = 94;
					crossRead(basic, generated, cluster, value, DynamicReference::new, actual -> {
						if(item == list){
							assertThat(actual.value).isInstanceOf(ContiguousIOList.class);
							@SuppressWarnings("unchecked") var actualList = (ContiguousIOList<Integer>)actual.value;
							assertList(actualList, list);
						}else assertThat(actual.value).isEqualTo(item);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}

	@FunctionalInterface
	private interface Check<T>{ void check(T value) throws IOException; }

	private static <T extends IOInstance<T>> void crossRead(StructPipe<T> basic, StructPipe<T> generated, DataProvider provider,
	                                                        T value, Supplier<T> factory, Check<T> check) throws IOException{
		var source = AllocateTicket.bytes(1024).submit(provider);
		var rewritten = AllocateTicket.bytes(1024).submit(provider);
		basic.write(source, value);
		for(var read : List.of(generated.readNew(source, null), readInto(generated, source, factory.get()))){
			check.check(read);
			generated.write(rewritten, read);
			check.check(basic.readNew(rewritten, null));
		}
	}

	private static <T extends IOInstance<T>> T readInto(StructPipe<T> pipe, Chunk chunk, T value) throws IOException{
		try(var io = chunk.io()){
			pipe.read(chunk.getDataProvider(), io, value, null);
		}
		return value;
	}

	private static <T> void assertList(ContiguousIOList<T> actual, ContiguousIOList<T> expected) throws IOException{
		assertThat(actual.getPointer()).isEqualTo(expected.getPointer());
		assertThat(actual.iterator().toList()).containsExactlyElementsOf(expected.iterator().toList());
	}

	private static void assertGenerated(StructPipe<?> pipe){
		if(pipe instanceof CheckedPipe.Standard<?> checked) pipe = checked.getUncheckedPipe();
		if(pipe instanceof CheckedPipe.Fixed<?> checked) pipe = checked.getUncheckedPipe();
		assertThat(pipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
	}
}
