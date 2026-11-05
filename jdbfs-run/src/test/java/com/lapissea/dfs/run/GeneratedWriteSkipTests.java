package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.UnmanagedRegistry;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.IOTransactionState;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.content.ContentInputStream;
import com.lapissea.dfs.io.content.ContentOutputBuilder;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.BaseFixedStructPipe;
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
import com.lapissea.dfs.type.field.VaryingSize;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GeneratedWriteSkipTests{
	private static final int SENTINEL = 0x12345678;
	
	@IOValue
	@StructPipe.Special
	public static class Child extends IOInstance.Managed<Child>{
		private static boolean forbidConstruction;
		@IODependency.VirtualNumSize
		private        long    value;
		public Child(){
			if(forbidConstruction) throw new AssertionError("Skip decoded an irrelevant child");
		}
		public Child(long value){
			this();
			this.value = value;
		}
	}
	
	@IOValue
	@StructPipe.Special
	public static class Numbers extends IOInstance.Managed<Numbers>{
		@IODependency.VirtualNumSize
		private long number;
		private int  count;
		private byte tail;
		public Numbers(){ }
	}
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"left", "right", "tail"})
	public static class Nested extends IOInstance.Managed<Nested>{
		private Child left;
		private Child right;
		private byte  tail;
		public Nested(){ }
	}
	
	@StructPipe.Special
	public static class GetterValue extends IOInstance.Managed<GetterValue>{
		private static   int getterCalls;
		@IOValue private int value;
		public GetterValue()         { }
		public GetterValue(int value){ this.value = value; }
		@IOValue
		public int getValue(){
			getterCalls++;
			return value;
		}
	}
	
	@StructPipe.Special
	@IOInstance.Order({"number", "child", "tail"})
	public interface Definition extends IOInstance.Def<Definition>{
		Optional<Integer> number();
		Child child();
		byte tail();
	}
	
	@StructPipe.Special
	public interface MutableDefinition extends IOInstance.Def<MutableDefinition>{
		@IODependency.VirtualNumSize
		long number();
		void number(long value);
		int count();
		void count(int value);
		byte tail();
		void tail(byte value);
	}
	
	@IOValue
	@StructPipe.Special
	public static class NullableOptional extends IOInstance.Managed<NullableOptional>{
		private Optional<Integer> number        = Optional.empty();
		private Optional<Child>   optionalChild = Optional.empty();
		@IONullability(IONullability.Mode.NULLABLE)
		private Child             nullable;
		private byte              tail;
		public NullableOptional(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class Defaulted extends IOInstance.Managed<Defaulted>{
		@IONullability(IONullability.Mode.DEFAULT_IF_NULL)
		private Child child;
		private byte  tail;
		public Defaulted(){ }
	}
	
	public enum Choice{A, B, C, D, E}
	
	@IOValue
	@StructPipe.Special
	public static class SkipArrays extends IOInstance.Managed<SkipArrays>{
		@IONullability(IONullability.Mode.NULLABLE) private boolean[] flags;
		@IONullability(IONullability.Mode.NULLABLE) private byte[]    bytes;
		@IONullability(IONullability.Mode.NULLABLE) private float[]   floats;
		@IONullability(IONullability.Mode.NULLABLE) private int[]     integers;
		@IONullability(IONullability.Mode.NULLABLE) private Choice[]  choices;
		private                                             byte      tail;
		public SkipArrays(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class Collections extends IOInstance.Managed<Collections>{
		private Integer[]         integers;
		private List<Integer>     integerList;
		private Choice[]          choices;
		private List<Choice>      choiceList;
		private Child[]           children;
		private List<Child>       childList;
		private String[]          strings;
		private ArrayList<String> stringList;
		private byte              tail;
		public Collections(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class References extends IOInstance.Managed<References>{
		private ContiguousIOList<Integer> list;
		@IOValue.Generic
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Object                    dynamic;
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Child                     child;
		private byte                      tail;
		public References(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class FailingReference extends IOInstance.Managed<FailingReference>{
		@IOValue.Reference private Child child;
		public FailingReference(){ }
	}
	
	@Test
	public void generatedWriterPreservesFieldDiagnosticsOnIOException() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(FailingReference.class, StagedInit.STATE_DONE);
				assertGeneratedHooks(pipe);
				var basic   = interpreted(pipe);
				var failure = new AtomicReference<IOException>();
				var memory  = MemoryData.empty();
				var source = new IOInterface(){
					@Override
					public boolean isReadOnly(){ return false; }
					@Override
					public IOTransaction startTransaction(){ return memory.startTransaction(); }
					@Override
					public IOTransactionState.Access lockIO(){ return memory.lockIO(); }
					@Override
					public UnmanagedRegistry getUnmanagedRegistry(){ return memory.getUnmanagedRegistry(); }
					@Override
					public UnmanagedRegistry claimUnmanagedRegistry(boolean readOnly){ return memory.claimUnmanagedRegistry(readOnly); }
					@Override
					public RandomIO io() throws IOException{
						var error = failure.get();
						if(error != null) throw error;
						return memory.io();
					}
				};
				var provider = Cluster.init(source);
				var value    = new FailingReference();
				value.child = new Child(-300);
				// Allocate the child reference before enabling failures, keeping generators out of the failure path.
				basic.write(provider, new ContentOutputBuilder(), value);
				var diagnostics = new ArrayList<List<String>>();
				for(var writer : List.of(basic, pipe)){
					var error = new IOException("referenced payload write failed");
					failure.set(error);
					try{
						assertThatThrownBy(() -> writer.write(provider, new ContentOutputBuilder(), value))
							.isSameAs(error);
						var messages = Arrays.stream(error.getSuppressed()).map(Throwable::getMessage).toList();
						assertThat(messages).anySatisfy(message -> assertThat(message).contains("Failed to write", "child"));
						diagnostics.add(messages);
					}finally{
						failure.set(null);
					}
				}
				assertThat(diagnostics.get(1)).containsExactlyElementsOf(diagnostics.get(0));
			}
		}
	}
	
	@Test
	public void generatedWritersMatchPrimitiveBytesAcrossPipeLayouts() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var struct  = Struct.of(Numbers.class, StagedInit.STATE_DONE);
				var varying = FixedVaryingStructPipe.tryVarying(struct, VaryingSize.Provider.constLimit(NumberSize.INT, 0));
				for(var pipe : List.<StructPipe<Numbers>>of(StandardStructPipe.of(Numbers.class, StagedInit.STATE_DONE),
				                                            FixedStructPipe.of(Numbers.class, StagedInit.STATE_DONE), varying)){
					for(long number : new long[]{0, -300, Integer.MIN_VALUE, Integer.MAX_VALUE}){
						var value = new Numbers();
						value.number = number;
						value.count = 70_000;
						value.tail = 91;
						assertParityAndSkip(pipe, interpreted(pipe), DataProvider.newVerySimpleProvider(), value, actual -> {
							assertThat(actual.number).isEqualTo(value.number);
							assertThat(actual.count).isEqualTo(value.count);
							assertThat(actual.tail).isEqualTo(value.tail);
						});
					}
				}
			}
		}
	}
	
	@Test
	public void generatedWritersKeepDistinctNestedWidthsAndReportTooSmall() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = FixedVaryingStructPipe.tryVarying(Struct.of(Nested.class),
				                                             VaryingSize.Provider.repeat(List.of(NumberSize.BYTE, NumberSize.LONG, NumberSize.BYTE)));
				var basic = interpreted(pipe);
				var value = new Nested();
				value.left = new Child(-100);
				value.right = new Child(5_000_000_000L);
				value.tail = 92;
				var provider = DataProvider.newVerySimpleProvider();
				assertParityAndSkip(pipe, basic, provider, value, actual -> {
					assertThat(actual.left.value).isEqualTo(value.left.value);
					assertThat(actual.right.value).isEqualTo(value.right.value);
					assertThat(actual.tail).isEqualTo(value.tail);
				});
				value.left.value = 300;
				for(var writer : List.of(pipe, basic)){
					assertThatThrownBy(() -> writer.write(provider, new ContentOutputBuilder(), value))
						.isInstanceOfSatisfying(VaryingSize.TooSmall.class, error -> {
							assertThat(error.tooSmallIdMap).containsOnlyKeys(new VaryingSize(NumberSize.BYTE, 0));
							assertThat(error.tooSmallIdMap.get(new VaryingSize(NumberSize.BYTE, 0))).isEqualTo(NumberSize.SHORT);
						});
				}
			}
		}
	}
	
	@Test
	public void generatedWritersUseMutableAndDefinitionGetters() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var getterPipe = StandardStructPipe.of(GetterValue.class, StagedInit.STATE_DONE);
				GetterValue.getterCalls = 0;
				assertParityAndSkip(getterPipe, interpreted(getterPipe), DataProvider.newVerySimpleProvider(),
				                    new GetterValue(-300), actual -> assertThat(actual.value).isEqualTo(-300));
				assertThat(GetterValue.getterCalls).isGreaterThanOrEqualTo(2);
				var definitionPipe = StandardStructPipe.of(Definition.class, StagedInit.STATE_DONE);
				for(boolean present : new boolean[]{false, true}){
					var number = present? Optional.of(-300) : Optional.<Integer>empty();
					var value  = IOInstance.Def.of(Definition.class, number, new Child(70_000), (byte)93);
					assertParityAndSkip(definitionPipe, interpreted(definitionPipe), DataProvider.newVerySimpleProvider(), value, actual -> {
						assertThat(actual.number()).isEqualTo(number);
						assertThat(actual.child().value).isEqualTo(70_000);
						assertThat(actual.tail()).isEqualTo((byte)93);
					});
				}
			}
		}
	}
	
	@Test
	public void generatedMutableDefinitionsWithoutDataConstructorRoundTrip() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var type    = Struct.of(MutableDefinition.class, StagedInit.STATE_DONE);
				var varying = FixedVaryingStructPipe.tryVarying(type, VaryingSize.Provider.constLimit(NumberSize.INT, 0));
				var value   = IOInstance.Def.of(MutableDefinition.class);
				value.number(-70_000);
				value.count(300);
				value.tail((byte)91);
				for(var pipe : List.<StructPipe<MutableDefinition>>of(StandardStructPipe.of(type, StagedInit.STATE_DONE),
				                                                      FixedStructPipe.of(type, StagedInit.STATE_DONE), varying)){
					assertParityAndSkip(pipe, interpreted(pipe), DataProvider.newVerySimpleProvider(), value, actual -> {
						assertThat(actual.number()).isEqualTo(value.number());
						assertThat(actual.count()).isEqualTo(value.count());
						assertThat(actual.tail()).isEqualTo(value.tail());
					});
				}
			}
		}
	}
	
	@Test
	public void generatedWritersPreserveOptionalNullableAndDefaultValues() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(NullableOptional.class, StagedInit.STATE_DONE);
				for(boolean present : new boolean[]{false, true}){
					var value = new NullableOptional();
					value.number = present? Optional.of(-300) : Optional.empty();
					value.optionalChild = present? Optional.of(new Child(70_000)) : Optional.empty();
					value.nullable = present? new Child(5_000_000_000L) : null;
					value.tail = 94;
					assertParityAndSkip(pipe, interpreted(pipe), DataProvider.newVerySimpleProvider(), value, actual -> {
						assertThat(actual.number).isEqualTo(value.number);
						assertThat(actual.optionalChild).isEqualTo(value.optionalChild);
						assertThat(actual.nullable).isEqualTo(value.nullable);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
				StructPipe<Defaulted> defaultPipe;
				try(var integrity = ConfigDefs.DO_INTEGRITY_CHECK.temporarySet(false)){
					defaultPipe = StandardStructPipe.of(Defaulted.class, StagedInit.STATE_DONE);
				}
				var value = new Defaulted();
				value.tail = 95;
				assertParityAndSkip(defaultPipe, interpreted(defaultPipe), DataProvider.newVerySimpleProvider(), value, actual -> {
					assertThat(actual.child.value).isZero();
					assertThat(actual.tail).isEqualTo(value.tail);
				});
			}
		}
	}
	
	@Test
	public void generatedCollectionWritersMatchBytesAndSkipWithoutDecodingChildren() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(Collections.class, StagedInit.STATE_DONE);
				for(boolean empty : new boolean[]{false, true}){
					var value = new Collections();
					value.integers = empty? new Integer[0] : new Integer[]{-300, 70_000};
					value.integerList = List.of(value.integers);
					value.choices = empty? new Choice[0] : new Choice[]{Choice.E, Choice.B, Choice.A};
					value.choiceList = List.of(value.choices);
					value.children = empty? new Child[0] : new Child[]{new Child(-300), new Child(5_000_000_000L)};
					value.childList = List.of(value.children);
					value.strings = empty? new String[0] : new String[]{"", "hello", "ž 中文 🦊"};
					value.stringList = new ArrayList<>(List.of(value.strings));
					value.tail = 96;
					assertParityAndSkip(pipe, interpreted(pipe), DataProvider.newVerySimpleProvider(), value, actual -> {
						assertThat(actual.integers).isEqualTo(value.integers);
						assertThat(actual.integerList).isEqualTo(value.integerList);
						assertThat(actual.choices).isEqualTo(value.choices);
						assertThat(actual.choiceList).isEqualTo(value.choiceList);
						assertThat(actual.children).isEqualTo(value.children);
						assertThat(actual.childList).isEqualTo(value.childList);
						assertThat(actual.strings).isEqualTo(value.strings);
						assertThat(actual.stringList).isEqualTo(value.stringList);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}
	
	@Test
	public void generatedArraySkipsRoundUpPartialBytes() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe     = StandardStructPipe.of(SkipArrays.class, StagedInit.STATE_DONE);
				var basic    = interpreted(pipe);
				var provider = DataProvider.newVerySimpleProvider();
				for(int length : new int[]{-1, 0, 1, 2, 3, 7, 8, 9, 15, 16, 17}){
					var value = new SkipArrays();
					if(length>=0){
						value.flags = new boolean[length];
						value.bytes = new byte[length];
						value.floats = new float[length];
						value.integers = new int[length];
						value.choices = new Choice[length];
						for(int i = 0; i<length; i++){
							value.flags[i] = i%2 == 0;
							value.bytes[i] = (byte)(i - 9);
							value.floats[i] = i + 0.5F;
							value.integers[i] = i%2 == 0? -70_000 : 70_000;
							value.choices[i] = Choice.values()[i%Choice.values().length];
						}
					}
					value.tail = 98;
					assertParityAndSkip(pipe, basic, provider, value, actual -> {
						assertThat(actual.flags).isEqualTo(value.flags);
						assertThat(actual.bytes).isEqualTo(value.bytes);
						assertThat(actual.floats).isEqualTo(value.floats);
						assertThat(actual.integers).isEqualTo(value.integers);
						assertThat(actual.choices).isEqualTo(value.choices);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}
	
	@Test
	public void generatedReferenceWritersKeepMetadataAndSkipPayloads() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var                       pipe    = StandardStructPipe.of(References.class, StagedInit.STATE_DONE);
				var                       cluster = Cluster.emptyMem();
				ContiguousIOList<Integer> list    = cluster.roots().request("writerList", ContiguousIOList.class, Integer.class);
				list.addAll(List.of(-300, 70_000));
				for(Object dynamic : new Object[]{null, "dynamic", new Child(5_000_000_000L)}){
					var value = new References();
					value.list = list;
					value.dynamic = dynamic;
					value.child = dynamic == null? null : new Child(-300);
					value.tail = 97;
					assertParityAndSkip(pipe, interpreted(pipe), cluster, value, actual -> {
						assertThat(actual.list.getPointer()).isEqualTo(list.getPointer());
						assertThat(actual.list.iterator().toList()).containsExactly(-300, 70_000);
						assertThat(actual.dynamic).isEqualTo(dynamic);
						assertThat(actual.child).isEqualTo(value.child);
						assertThat(actual.tail).isEqualTo(value.tail);
					});
				}
			}
		}
	}
	
	@FunctionalInterface
	private interface Check<T>{
		void check(T value) throws IOException;
	}
	
	private static <T extends IOInstance<T>> void assertParityAndSkip(StructPipe<T> generated, StructPipe<T> basic,
	                                                                  DataProvider provider, T value, Check<T> check) throws IOException{
		assertGeneratedHooks(generated);
		var expected = new ContentOutputBuilder();
		var actual   = new ContentOutputBuilder();
		basic.write(provider, expected, value);
		generated.write(provider, actual, value);
		assertThat(actual.toByteArray()).containsExactly(expected.toByteArray());
		check.check(generated.readNew(provider, new ContentInputStream.BA(expected.toByteArray()), null));
		check.check(basic.readNew(provider, new ContentInputStream.BA(actual.toByteArray()), null));
		actual.writeInt4(SENTINEL);
		Child.forbidConstruction = true;
		try{
			var reader = new ContentInputStream.BA(actual.toByteArray());
			generated.skip(provider, reader, null);
			assertThat(reader.readInt4()).isEqualTo(SENTINEL);
			assertThat(reader.available()).isZero();
		}finally{
			Child.forbidConstruction = false;
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> StructPipe<T> interpreted(StructPipe<T> generated){
		var raw = unwrap(generated);
		if(raw instanceof FixedVaryingStructPipe<?> varying){
			return new FixedVaryingStructPipe<>((FixedVaryingStructPipe<T>)varying, StagedInit.STATE_DONE);
		}
		if(raw instanceof FixedStructPipe<?>) return new FixedStructPipe<>(generated.getType(), StagedInit.STATE_DONE);
		return new StandardStructPipe<>(generated.getType(), StagedInit.STATE_DONE);
	}
	
	private static StructPipe<?> unwrap(StructPipe<?> pipe){
		if(pipe instanceof CheckedPipe.Standard<?> checked) pipe = checked.getUncheckedPipe();
		if(pipe instanceof CheckedPipe.Fixed<?> checked) pipe = checked.getUncheckedPipe();
		return pipe;
	}
	
	private static void assertGeneratedHooks(StructPipe<?> pipe){
		pipe = unwrap(pipe);
		assertThat(pipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
		var declared = Arrays.stream(pipe.getClass().getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
		assertThat(declared).contains("writeFields");
		if(!(pipe instanceof BaseFixedStructPipe<?>)) assertThat(declared).contains("skipFields");
	}
}
