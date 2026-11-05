package com.lapissea.dfs.run;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.exceptions.LockedFlagSet;
import com.lapissea.dfs.io.instancepipe.CheckedPipe;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.StagedInit;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PipeReferenceFieldCodegenTests{
	@IOValue
	@StructPipe.Special
	public static class Child extends IOInstance.Managed<Child>{
		@IODependency.VirtualNumSize
		private long value;
		public Child()          { }
		public Child(long value){ this.value = value; }
	}
	
	@IOValue
	@StructPipe.Special
	public static class References extends IOInstance.Managed<References>{
		@IOValue.Reference
		private Child flexible;
		@IOValue.Reference(dataPipeType = IOValue.Reference.PipeType.FIXED)
		private Child fixed;
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Child nullable;
		private byte  tail;
		public References(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class DefaultReference extends IOInstance.Managed<DefaultReference>{
		@IOValue.Reference
		@IONullability(IONullability.Mode.DEFAULT_IF_NULL)
		private Child defaulted;
		public DefaultReference(){ }
	}
	
	@IOValue
	@StructPipe.Special
	@IOInstance.Order({"child", "nullable", "tail", "child:ref", "nullable:ref"})
	public static class ConstructorReferences extends IOInstance.Managed<ConstructorReferences>{
		@IOValue.Reference
		private Child child;
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Child nullable;
		private byte  tail;
		public ConstructorReferences(){ }
		public ConstructorReferences(Child child, Child nullable, byte tail){
			this.child = child;
			this.nullable = nullable;
			this.tail = tail;
		}
		public ConstructorReferences(Child child, Child nullable, byte tail, Reference childRef, Reference nullableRef){
			this(child, nullable, tail);
		}
	}
	
	@Test
	public void constructorReadersPreserveReferenceMetadataForReserialization() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(ConstructorReferences.class, StagedInit.STATE_DONE);
				assertGenerated(pipe);
				var provider  = DataProvider.newVerySimpleProvider();
				var chunk     = AllocateTicket.bytes(128).submit(provider);
				var rewritten = AllocateTicket.bytes(128).submit(provider);
				for(boolean nullChild : new boolean[]{false, true}){
					var value = new ConstructorReferences(new Child(5_000_000_000L), nullChild? null : new Child(-300), (byte)98);
					pipe.write(chunk, value);
					for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new ConstructorReferences()))){
						assertThat(read.child.value).isEqualTo(value.child.value);
						assertThat(read.nullable).isEqualTo(value.nullable);
						assertThat(read.tail).isEqualTo(value.tail);
						pipe.write(rewritten, read);
						var reread = pipe.readNew(rewritten, null);
						assertThat(reread.child.value).isEqualTo(value.child.value);
						assertThat(reread.nullable).isEqualTo(value.nullable);
						assertThat(reread.tail).isEqualTo(value.tail);
					}
				}
			}
		}
	}
	
	@IOValue
	@StructPipe.Special
	public static class Strings extends IOInstance.Managed<Strings>{
		private String[]          array;
		private List<String>      list;
		private ArrayList<String> arrayList;
		@IONullability(IONullability.Mode.NULLABLE)
		private String[]          nullable;
		private byte              tail;
		public Strings(){ }
	}
	
	public abstract static sealed class SealedChild extends IOInstance.Managed<SealedChild> permits First, Second{ }
	
	public static final class First extends SealedChild{
		@IOValue private int value;
		public First()         { }
		public First(int value){ this.value = value; }
	}
	
	public static final class Second extends SealedChild{
		@IOValue private long value;
		public Second()          { }
		public Second(long value){ this.value = value; }
	}
	
	@IOValue
	@StructPipe.Special
	public static class ObjectCollections extends IOInstance.Managed<ObjectCollections>{
		private Child[]     array;
		private List<Child> list;
		@IOValue.Reference
		private Child[]     referencedArray;
		@IOValue.Reference
		private List<Child> referencedList;
		@IOValue.Reference
		@IONullability(IONullability.Mode.NULLABLE)
		private Child[]     nullableReference;
		@IONullability(IONullability.Mode.NULLABLE)
		private Child[]     nullable;
		private byte        tail;
		public ObjectCollections(){ }
	}
	
	@IOValue
	@StructPipe.Special
	public static class SealedObjects extends IOInstance.Managed<SealedObjects>{
		private SealedChild       child;
		@IONullability(IONullability.Mode.NULLABLE)
		private SealedChild       nullable;
		private List<SealedChild> list;
		private byte              tail;
		public SealedObjects(){ }
	}
	
	@Test
	public void generatedObjectCollectionReadersPreserveChildValues() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(ObjectCollections.class, StagedInit.STATE_DONE);
				assertGenerated(pipe);
				var chunk = AllocateTicket.bytes(1024).submit(DataProvider.newVerySimpleProvider());
				for(boolean empty : new boolean[]{false, true}){
					var value = new ObjectCollections();
					value.array = empty? new Child[0] : new Child[]{new Child(-300), new Child(5_000_000_000L)};
					value.list = List.of(value.array);
					value.referencedArray = value.array;
					value.referencedList = value.list;
					value.nullableReference = empty? null : value.array;
					value.nullable = empty? null : value.array;
					value.tail = 43;
					pipe.write(chunk, value);
					for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new ObjectCollections()))){
						assertThat(read.array).isEqualTo(value.array);
						assertThat(read.list).isEqualTo(value.list);
						assertThat(read.referencedArray).isEqualTo(value.referencedArray);
						assertThat(read.referencedList).isEqualTo(value.referencedList);
						assertThat(read.nullableReference).isEqualTo(value.nullableReference);
						assertThat(read.nullable).isEqualTo(value.nullable);
						assertThat(read.tail).isEqualTo(value.tail);
					}
				}
			}
		}
	}
	
	@Test
	public void generatedSealedReadersSelectActualChildPipe() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(SealedObjects.class, StagedInit.STATE_DONE);
				assertGenerated(pipe);
				var chunk = AllocateTicket.bytes(1024).submit(DataProvider.newVerySimpleProvider());
				for(boolean nullChild : new boolean[]{false, true}){
					var value = new SealedObjects();
					value.child = nullChild? new First(-300) : new Second(5_000_000_000L);
					value.nullable = nullChild? null : new First(97);
					value.list = List.of(new First(300), new Second(-70_000));
					value.tail = 44;
					pipe.write(chunk, value);
					for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new SealedObjects()))){
						assertThat(read.child).isEqualTo(value.child);
						assertThat(read.nullable).isEqualTo(value.nullable);
						assertThat(read.list).isEqualTo(value.list);
						assertThat(read.tail).isEqualTo(value.tail);
					}
				}
			}
		}
	}
	
	@Test
	public void generatedReferenceReadersUseConfiguredChildPipes() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(References.class, StagedInit.STATE_DONE);
				assertGenerated(pipe);
				var provider = DataProvider.newVerySimpleProvider();
				var chunk    = AllocateTicket.bytes(128).submit(provider);
				for(boolean nullChild : new boolean[]{false, true}){
					var value = new References();
					value.flexible = new Child(-300);
					value.fixed = new Child(5_000_000_000L);
					value.nullable = nullChild? null : new Child(70_000);
					value.tail = 97;
					pipe.write(chunk, value);
					for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new References()))){
						assertThat(read.flexible.value).isEqualTo(value.flexible.value);
						assertThat(read.fixed.value).isEqualTo(value.fixed.value);
						if(nullChild) assertThat(read.nullable).isNull();
						else assertThat(read.nullable.value).isEqualTo(value.nullable.value);
						assertThat(read.tail).isEqualTo(value.tail);
					}
				}
			}
		}
	}
	
	@Test
	public void generatedDefaultReferenceReadersMakeChildForNullPointer() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				StructPipe<DefaultReference> pipe;
				// The existing integrity check compares a raw null against DEFAULT_IF_NULL's normalized child.
				try(var integrity = ConfigDefs.DO_INTEGRITY_CHECK.temporarySet(false)){
					pipe = StandardStructPipe.of(DefaultReference.class, StagedInit.STATE_DONE);
				}
				assertGenerated(pipe);
				var chunk = AllocateTicket.bytes(128).submit(DataProvider.newVerySimpleProvider());
				// This holder stores only its companion Reference; serialize a null pointer directly.
				StandardStructPipe.of(Reference.class).write(chunk, Reference.NULL);
				for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new DefaultReference()))){
					assertThat(read.defaulted.value).isZero();
				}
			}
		}
	}
	
	@Test
	public void generatedStringCollectionReadersKeepLengthsAndNulls() throws IOException, LockedFlagSet{
		synchronized(ConfigDefs.OPTIMIZED_PIPE){
			try(var ignored = ConfigDefs.OPTIMIZED_PIPE.temporarySet(ConfigDefs.PipeOptimization.SPECIAL_ONLY)){
				var pipe = StandardStructPipe.of(Strings.class, StagedInit.STATE_DONE);
				assertGenerated(pipe);
				var chunk = AllocateTicket.bytes(1024).submit(DataProvider.newVerySimpleProvider());
				for(boolean empty : new boolean[]{false, true}){
					var value = new Strings();
					value.array = empty? new String[0] : new String[]{"", "hello", "ž 中文 🦊"};
					value.list = empty? List.of() : List.of("abc", "def");
					value.arrayList = new ArrayList<>(value.list);
					value.nullable = empty? null : new String[]{"nullable"};
					value.tail = 42;
					pipe.write(chunk, value);
					for(var read : List.of(pipe.readNew(chunk, null), readInto(pipe, chunk, new Strings()))){
						assertThat(read.array).containsExactly(value.array);
						assertThat(read.list).isEqualTo(value.list);
						assertThat(read.arrayList).isEqualTo(value.arrayList);
						assertThat(read.nullable).isEqualTo(value.nullable);
						assertThat(read.tail).isEqualTo(value.tail);
					}
				}
			}
		}
	}
	
	private static <T extends IOInstance<T>> T readInto(StructPipe<T> pipe, Chunk chunk, T value) throws IOException{
		try(var io = chunk.io()){
			pipe.read(chunk.getDataProvider(), io, value, null);
		}
		return value;
	}
	
	private static void assertGenerated(StructPipe<?> pipe){
		if(pipe instanceof CheckedPipe.Standard<?> checked) pipe = checked.getUncheckedPipe();
		assertThat(pipe).isInstanceOf(StructPipe.SpecializedImplementation.class);
	}
}
